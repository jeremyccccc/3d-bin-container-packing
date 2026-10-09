package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.service.service.mapping.PackingMapper;

import static com.github.skjolber.packing.service.service.PackingDiagnostics.elapsedMillis;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.houseBillCount;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.houseBillIds;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.percent;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.rounded;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.safe;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.totalVolume;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.totalWeight;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.unitCount;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

/** Validates one whole-order assignment by packing each assigned container. */
final class WholeOrderCandidateValidator {

	@FunctionalInterface
	interface ContainerPacker {
		PackagerResult pack(PackingPlan plan, long deadline, PackingLogContext context);
	}

	record Result(boolean success, PackagerResult result) {
	}

	private final boolean diagnosticsEnabled;

	WholeOrderCandidateValidator(boolean diagnosticsEnabled) {
		this.diagnosticsEnabled = diagnosticsEnabled;
	}

	Result validate(PackingPlan plan, List<ContainerItem> physicalContainers,
			WholeOrderAssignmentSolver.Candidate candidate, int attempt, long searchId, long deadline,
			WholeOrderPackingCache cache, ContainerPacker packer) {
		long candidateStarted = System.nanoTime();
		System.out.println("packing-service whole-order candidate-start searchId=" + searchId
				+ " attempt=" + attempt + " source=" + safe(candidate.source())
				+ " generationIndex=" + (candidate.generationIndex() + 1)
				+ candidate.score().logFields()
				+ " containers=" + candidate.itemsByContainer().size()
				+ " worker=" + Thread.currentThread().getName());
		List<Container> packedContainers = new ArrayList<>();
		long duration = 0L;
		boolean success = true;
		int containersAttempted = 0;
		int failedContainer = -1;
		String failureReason = "none";
		for (int i : candidate.score().validationOrder()) {
			if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= deadline) {
				success = false;
				failureReason = Thread.currentThread().isInterrupted() ? "cancelled" : "deadline";
				break;
			}
			List<BoxItem> items = candidate.itemsByContainer().get(i);
			if (items.isEmpty()) {
				System.out.println("packing-service whole-order container-skip searchId=" + searchId
						+ " attempt=" + attempt + " source=" + safe(candidate.source())
						+ " container=" + (i + 1) + "/" + physicalContainers.size()
						+ " containerId=" + safe(physicalContainers.get(i).getContainer().getId())
						+ " reason=empty");
				continue;
			}
			containersAttempted++;
			Container container = physicalContainers.get(i).getContainer();
			PackingLogContext context = new PackingLogContext(searchId, attempt, candidate.source(),
					i + 1, physicalContainers.size(), container.getId());
			long containerStarted = System.nanoTime();
			System.out.println("packing-service whole-order container-start" + context.fields()
					+ " validationRank=" + containersAttempted
					+ " predictedRisk=" + rounded(candidate.score().containerRisks().get(i))
					+ " houseBills=" + houseBillCount(items) + " itemTypes=" + items.size()
					+ " units=" + unitCount(items) + " volume=" + totalVolume(items)
					+ " weight=" + totalWeight(items)
					+ " volumeFillPct=" + percent(totalVolume(items), container.getMaxLoadVolume())
					+ " weightFillPct=" + percent(totalWeight(items), container.getMaxLoadWeight())
					+ (diagnosticsEnabled ? " houseBillIds=" + houseBillIds(items) : ""));
			PackingPlan containerPlan = new PackingPlan(
					plan.requestedContainers(), List.of(physicalContainers.get(i)), items,
					plan.cargoLines(), plan.warnings());
			WholeOrderPackingCache.Lookup cached = cache.lookup(container, items);
			PackagerResult packed;
			if (cached.hit()) {
				System.out.println("packing-service whole-order cache-hit" + context.fields()
						+ " status=" + (cached.failed() ? "failure" : "success")
						+ " savedMs=" + cached.savedMillis());
				packed = cached.result();
			} else {
				System.out.println("packing-service whole-order cache-miss" + context.fields());
				long packingStarted = System.nanoTime();
				packed = packer.pack(containerPlan, deadline, context);
				long computationMillis = elapsedMillis(packingStarted);
				if (packed != null && packed.isSuccess() && packed.size() == 1) {
					cache.putSuccess(container, items, packed, computationMillis);
				} else {
					boolean completed = !Thread.currentThread().isInterrupted()
							&& System.currentTimeMillis() + 1_000L < deadline;
					cache.putFailure(container, items, computationMillis, completed);
				}
			}
			if (packed == null || !packed.isSuccess() || packed.size() != 1) {
				success = false;
				failedContainer = i + 1;
				failureReason = cached.hit() && cached.failed() ? "cached-failure"
						: Thread.currentThread().isInterrupted() ? "cancelled"
						: System.currentTimeMillis() >= deadline ? "deadline" : "no-algorithm-succeeded";
				System.out.println("packing-service whole-order container-end" + context.fields()
						+ " success=false elapsedMs=" + elapsedMillis(containerStarted)
						+ " reason=" + failureReason);
				break;
			}
			packedContainers.add(packed.get(0));
			duration += packed.getDuration();
			System.out.println("packing-service whole-order container-end" + context.fields()
					+ " success=true elapsedMs=" + elapsedMillis(containerStarted)
					+ " placements=" + packed.get(0).getStack().size());
		}
		PackagerResult result = null;
		if (success) {
			result = new PackagerResult(packedContainers, duration, false);
			if (splitsHouseBills(result)) {
				success = false;
				failureReason = "split-house-bill";
				result = null;
			} else {
				System.out.println("packing-service whole-order success source=" + candidate.source()
						+ " attempt=" + attempt + " containerCount=" + result.size());
			}
		}
		System.out.println("packing-service whole-order candidate-end searchId=" + searchId
				+ " attempt=" + attempt + " source=" + safe(candidate.source())
				+ " success=" + success + " containersAttempted=" + containersAttempted
				+ " failedContainer=" + failedContainer + " reason=" + failureReason
				+ " elapsedMs=" + elapsedMillis(candidateStarted));
		return new Result(success, result);
	}

	private static boolean splitsHouseBills(PackagerResult result) {
		Map<String, Integer> containersByHouseBill = new HashMap<>();
		for (int containerIndex = 0; containerIndex < result.getContainers().size(); containerIndex++) {
			Container container = result.getContainers().get(containerIndex);
			for (Placement placement : container.getStack().getPlacements()) {
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				if (houseBsId == null) continue;
				Integer previous = containersByHouseBill.putIfAbsent(houseBsId, containerIndex);
				if (previous != null && previous != containerIndex) return true;
			}
		}
		return false;
	}
}
