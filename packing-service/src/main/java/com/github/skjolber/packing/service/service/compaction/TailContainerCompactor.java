package com.github.skjolber.packing.service.service.compaction;

import com.github.skjolber.packing.service.service.blockbeam.BlockBeamSearchPackager;
import com.github.skjolber.packing.service.service.rule.PlacementGeometry;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.service.compaction.TailCompactionSupport.HouseBillLoad;

/** Moves complete house bills out of the least-filled container without moving existing front-container cargo. */
public final class TailContainerCompactor {

	private final PlacementSupport.Policy supportPolicy;
	private final int blockBeamWidth;
	private final int blockBranching;
	private final TailCompactionValidator validator;

	public TailContainerCompactor(PlacementSupport.Policy supportPolicy, int blockBeamWidth, int blockBranching) {
		this.supportPolicy = supportPolicy;
		this.blockBeamWidth = blockBeamWidth;
		this.blockBranching = blockBranching;
		this.validator = new TailCompactionValidator(supportPolicy);
	}

	public PackagerResult compact(PackagerResult source, long deadlineMillis) {
		if (source == null || !source.isSuccess() || source.size() < 2) return source;
		for (Container container : source.getContainers()) {
			PlacementGeometry.Validation geometry = PlacementGeometry.validate(container);
			if (!geometry.valid()) {
				System.err.println("packing-service tail-compaction skipped invalid baseline container="
						+ container.getId() + " reason=" + geometry.message());
				return source;
			}
		}
		long started = System.nanoTime();
		List<Container> containers = new ArrayList<>(source.getContainers());
		int tailIndex = TailCompactionSupport.leastFilledContainer(containers);
		Container originalTail = containers.get(tailIndex);
		long originalTailVolume = TailCompactionSupport.loadedVolume(originalTail);
		List<HouseBillLoad> houseBills = new ArrayList<>(TailCompactionSupport.houseBills(originalTail));
		houseBills.sort(Comparator.comparingLong(HouseBillLoad::volume).reversed()
				.thenComparing(HouseBillLoad::houseBsId));

		int attempts = 0;
		int directSuccesses = 0;
		int targetedAttempts = 0;
		int targetedSuccesses = 0;
		int directMovedBills = 0;
		int targetedMovedBills = 0;
		int movedBills = 0;
		long directMovedVolume = 0L;
		long targetedMovedVolume = 0L;
		long movedVolume = 0L;
		for (HouseBillLoad houseBill : houseBills) {
			if (expired(deadlineMillis)) break;
			if (!TailCompactionSupport.canPack(houseBill.items())) continue;
			List<Integer> targets = TailCompactionSupport.targetOrder(containers, tailIndex);
			PackagerResult selected = null;
			int selectedTargetIndex = -1;
			boolean selectedBySpaceGuidedRepair = false;
			for (int targetIndex : targets) {
				if (expired(deadlineMillis)) break;
				attempts++;
				Container target = containers.get(targetIndex);
				BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
						blockBeamWidth, blockBranching, supportPolicy);
				PackagerResult packed;
				try {
					packed = packager.pack(target, houseBill.items(),
							target.getStack().getPlacements(), boundedAttemptDeadline(deadlineMillis, 2_000L),
							" phase=tail-compaction houseBsId=" + houseBill.houseBsId()
									+ " target=" + target.getId());
				} catch (IllegalArgumentException e) {
					logRejectedAttempt("direct", houseBill.houseBsId(), target, e);
					continue;
				}
				if (!validator.validGeometry(packed, "direct", houseBill.houseBsId(), target)) continue;
				selected = packed;
				selectedTargetIndex = targetIndex;
				directSuccesses++;
				break;
			}
			if (selected == null) {
				for (int targetIndex : targets) {
					if (expired(deadlineMillis)) break;
					targetedAttempts++;
					Container target = containers.get(targetIndex);
					PackagerResult repaired;
					try {
						repaired = new SpaceGuidedTailRepair(supportPolicy,
								blockBeamWidth, blockBranching).repair(target, houseBill.items(),
								boundedAttemptDeadline(deadlineMillis, 4_500L),
								" houseBsId=" + houseBill.houseBsId() + " target=" + target.getId());
					} catch (IllegalArgumentException e) {
						logRejectedAttempt("space-guided", houseBill.houseBsId(), target, e);
						continue;
					}
					if (!validator.validGeometry(repaired, "space-guided", houseBill.houseBsId(), target)) continue;
					selected = repaired;
					selectedTargetIndex = targetIndex;
					selectedBySpaceGuidedRepair = true;
					targetedSuccesses++;
					break;
				}
			}
			if (selected != null) {

				Container nextTail = prepareTailAfterRemoval(
						containers.get(tailIndex), houseBill.houseBsId(), deadlineMillis);
				Container nextTarget = selected.get(0);
				if (nextTail == null || !validator.supported(nextTarget)) continue;

				containers.set(selectedTargetIndex, nextTarget);
				containers.set(tailIndex, nextTail);
				movedBills++;
				movedVolume += houseBill.volume();
				if (selectedBySpaceGuidedRepair) {
					targetedMovedBills++;
					targetedMovedVolume += houseBill.volume();
				} else {
					directMovedBills++;
					directMovedVolume += houseBill.volume();
				}
				System.out.println("packing-service tail-compaction move houseBsId=" + houseBill.houseBsId()
						+ " mode=" + (selectedBySpaceGuidedRepair ? "space-guided" : "direct")
						+ " target=" + nextTarget.getId() + " units=" + houseBill.units()
						+ " volume=" + houseBill.volume());
			}
		}

		long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
		if (movedBills == 0) {
			logSummary(originalTailVolume, 0L, 0, attempts, directSuccesses,
					0, 0L, targetedAttempts, targetedSuccesses, 0, 0L, elapsedMillis);
			return source;
		}
		if (containers.get(tailIndex).getStack().getPlacements().isEmpty()) containers.remove(tailIndex);
		if (!validator.validFinalResult(source.getContainers(), containers)) {
			System.out.println("packing-service tail-compaction success=false reason=final-validation"
					+ " movedBills=" + movedBills + " movedVolume=" + movedVolume
					+ " directMovedBills=" + directMovedBills + " directMovedVolume=" + directMovedVolume
					+ " targetedMovedBills=" + targetedMovedBills
					+ " targetedMovedVolume=" + targetedMovedVolume + " elapsedMs=" + elapsedMillis);
			return source;
		}
		logSummary(originalTailVolume, movedVolume, movedBills, attempts, directSuccesses,
				directMovedBills, directMovedVolume, targetedAttempts, targetedSuccesses,
				targetedMovedBills, targetedMovedVolume, elapsedMillis);
		return new PackagerResult(List.copyOf(containers), source.getDuration() + elapsedMillis, false);
	}

	private static void logSummary(long originalTailVolume, long movedVolume, int movedBills,
			int directAttempts, int directSuccesses, int directMovedBills, long directMovedVolume,
			int targetedAttempts, int targetedSuccesses, int targetedMovedBills, long targetedMovedVolume,
			long elapsedMillis) {
		System.out.println("packing-service tail-compaction success=true movedBills=" + movedBills
				+ " movedVolume=" + movedVolume + " originalTailVolume=" + originalTailVolume
				+ " remainingTailVolume=" + Math.max(0L, originalTailVolume - movedVolume)
				+ " attempts=" + directAttempts + " directSuccesses=" + directSuccesses
				+ " directMovedBills=" + directMovedBills + " directMovedVolume=" + directMovedVolume
				+ " targetedAttempts=" + targetedAttempts + " targetedSuccesses=" + targetedSuccesses
				+ " targetedMovedBills=" + targetedMovedBills
				+ " targetedMovedVolume=" + targetedMovedVolume + " elapsedMs=" + elapsedMillis);
	}

	private static void logRejectedAttempt(String phase, String houseBsId, Container target,
			IllegalArgumentException exception) {
		System.err.println("packing-service tail-compaction rejected phase=" + phase
				+ " houseBsId=" + houseBsId + " target=" + target.getId()
				+ " reason=" + exception.getMessage());
	}

	private Container prepareTailAfterRemoval(Container source, String houseBsId, long deadlineMillis) {
		Container remaining = TailCompactionSupport.withoutHouseBill(source, houseBsId);
		if (remaining.getStack().getPlacements().isEmpty() || validator.supported(remaining)) return remaining;
		if (!TailCompactionSupport.canRepack(remaining.getStack().getPlacements()) || expired(deadlineMillis)) return null;
		List<BoxItem> items = TailCompactionSupport.boxItems(remaining.getStack().getPlacements());
		BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
				blockBeamWidth, blockBranching, supportPolicy);
		PackagerResult repacked = packager.pack(remaining, items, deadlineMillis,
				" phase=tail-repack removedHouseBsId=" + houseBsId);
		if (repacked == null || !repacked.isSuccess() || !validator.supported(repacked.get(0))) return null;
		return repacked.get(0);
	}

	private static boolean expired(long deadlineMillis) {
		return deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private static long boundedAttemptDeadline(long deadlineMillis, long maximumMillis) {
		long candidate = System.currentTimeMillis() + maximumMillis;
		return deadlineMillis <= 0L ? candidate : Math.min(deadlineMillis, candidate);
	}

}
