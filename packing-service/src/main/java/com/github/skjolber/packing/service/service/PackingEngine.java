package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Packager;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.comparator.LargestAreaBoxItemComparator;
import com.github.skjolber.packing.comparator.VolumeThenWeightBoxItemComparator;
import com.github.skjolber.packing.packer.AbstractPackagerResultBuilder;
import com.github.skjolber.packing.packer.laff.FastLargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.laff.LargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.plain.PlainPackager;
import com.github.skjolber.packing.packer.plain.PlainPlacementComparator;
import com.github.skjolber.packing.service.dto.ContainerDto;

@Component
class PackingEngine {
	private static final long WHOLE_ORDER_SEARCH_MILLIS = 180_000L;
	private static final long BLOCK_BEAM_SEARCH_MILLIS = 30_000L;
	private static final long BLOCK_BEAM_QUICK_SEARCH_MILLIS = 2_000L;
	private static final int BLOCK_BEAM_INTENSIVE_WIDTH = 128;
	private static final int BLOCK_BEAM_INTENSIVE_BRANCHING = 64;
	private static final int BLOCK_BEAM_INTENSIVE_PROFILES = 2;
	private static final AtomicLong SEARCH_SEQUENCE = new AtomicLong();

	private final WholeOrderAssignmentSolver assignmentSolver = new WholeOrderAssignmentSolver();
	private final PlacementSupport.Policy supportPolicy;
	private final boolean diagnosticsEnabled;
	private final boolean blockBeamOnly;
	private final WholeOrderAssignmentStrategy assignmentStrategy;
	private final double assignmentTargetFillRatio;
	private final boolean tailCompactionEnabled;
	private final long tailCompactionSearchMillis;
	private final int tailCompactionBeamWidth;
	private final int tailCompactionBranching;
	private final int wholeOrderParallelism;

	PackingEngine() {
		this(PlacementSupport.DEFAULT_POLICY, false, false,
				WholeOrderAssignmentStrategy.BALANCED, 0.85, false, 30_000L, 32, 32, 1);
	}

	@Autowired
	PackingEngine(
			@Value("${packing.support.minimum-area-ratio:0.85}") double minimumAreaRatio,
			@Value("${packing.support.require-center:true}") boolean requireCenterSupport,
			@Value("${packing.support.maximum-overhang-mm:20}") int maximumOverhangMillimeters,
			@Value("${packing.support.maximum-overhang-ratio:0.05}") double maximumOverhangRatio,
			@Value("${packing.diagnostics.enabled:false}") boolean diagnosticsEnabled,
			@Value("${packing.block-beam-only:true}") boolean blockBeamOnly,
			@Value("${packing.assignment.strategy:fill-first}") String assignmentStrategy,
			@Value("${packing.assignment.target-fill-ratio:0.85}") double assignmentTargetFillRatio,
			@Value("${packing.tail-compaction.enabled:true}") boolean tailCompactionEnabled,
			@Value("${packing.tail-compaction.search-millis:60000}") long tailCompactionSearchMillis,
			@Value("${packing.tail-compaction.beam-width:32}") int tailCompactionBeamWidth,
			@Value("${packing.tail-compaction.branching:32}") int tailCompactionBranching,
			@Value("${packing.whole-order.parallelism:1}") int wholeOrderParallelism) {
		this(new PlacementSupport.Policy(minimumAreaRatio, requireCenterSupport,
				maximumOverhangMillimeters, maximumOverhangRatio), diagnosticsEnabled, blockBeamOnly,
				WholeOrderAssignmentStrategy.parse(assignmentStrategy), assignmentTargetFillRatio,
				tailCompactionEnabled, tailCompactionSearchMillis,
				tailCompactionBeamWidth, tailCompactionBranching, wholeOrderParallelism);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy) {
		this(supportPolicy, false, false, WholeOrderAssignmentStrategy.BALANCED, 0.85,
				false, 30_000L, 32, 32, 1);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled) {
		this(supportPolicy, diagnosticsEnabled, false, WholeOrderAssignmentStrategy.BALANCED, 0.85,
				false, 30_000L, 32, 32, 1);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly) {
		this(supportPolicy, diagnosticsEnabled, blockBeamOnly,
				WholeOrderAssignmentStrategy.BALANCED, 0.85, false, 30_000L, 32, 32, 1);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly,
			WholeOrderAssignmentStrategy assignmentStrategy, double assignmentTargetFillRatio) {
		this(supportPolicy, diagnosticsEnabled, blockBeamOnly, assignmentStrategy, assignmentTargetFillRatio,
				false, 30_000L, 32, 32, 1);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly,
			WholeOrderAssignmentStrategy assignmentStrategy, double assignmentTargetFillRatio,
			boolean tailCompactionEnabled, long tailCompactionSearchMillis,
			int tailCompactionBeamWidth, int tailCompactionBranching, int wholeOrderParallelism) {
		if (assignmentTargetFillRatio <= 0.0 || assignmentTargetFillRatio > 1.0) {
			throw new IllegalArgumentException("packing.assignment.target-fill-ratio must be in (0, 1]");
		}
		if (tailCompactionSearchMillis <= 0L || tailCompactionBeamWidth <= 0 || tailCompactionBranching <= 0) {
			throw new IllegalArgumentException("tail compaction search settings must be positive");
		}
		if (wholeOrderParallelism <= 0 || wholeOrderParallelism > 16) {
			throw new IllegalArgumentException("packing.whole-order.parallelism must be in [1, 16]");
		}
		this.supportPolicy = supportPolicy;
		this.diagnosticsEnabled = diagnosticsEnabled;
		this.blockBeamOnly = blockBeamOnly;
		this.assignmentStrategy = assignmentStrategy;
		this.assignmentTargetFillRatio = assignmentTargetFillRatio;
		this.tailCompactionEnabled = tailCompactionEnabled;
		this.tailCompactionSearchMillis = tailCompactionSearchMillis;
		this.tailCompactionBeamWidth = tailCompactionBeamWidth;
		this.tailCompactionBranching = tailCompactionBranching;
		this.wholeOrderParallelism = wholeOrderParallelism;
	}

	PackagerResult pack(PackingPlan plan) {
		PackingOutcome outcome = packOutcome(plan);
		return outcome.targetAchieved() ? outcome.result() : null;
	}

	PackingOutcome packOutcome(PackingPlan plan) {
		if (plan.containerItems().isEmpty() || plan.boxItems().isEmpty()) {
			return new PackingOutcome(plan, null, false, false,
					totalContainerCount(plan.containerItems()));
		}
		int targetContainerCount = totalContainerCount(plan.containerItems());

		// Use the same production path for every target count, including one
		// container: fill-first assignment, Balanced Block Beam, original/swapped
		// orientations and first complete solution. This avoids the four-profile
		// quality search, gap filling and LNS used by the standalone flat-search
		// mode.
		PackagerResult targetResult = packWholeOrders(plan, false, targetContainerCount);
		if (targetResult != null && targetResult.isSuccess()) {
			return new PackingOutcome(plan, targetResult, true, false, targetContainerCount);
		}

		PackingPlan fallbackPlan = withAutomatic40Hq(plan);
		System.out.println("packing-service automatic-fallback start targetContainers="
				+ targetContainerCount + " fallbackContainers=" + (targetContainerCount + 1)
				+ " addedContainerId="
				+ fallbackPlan.requestedContainers().get(fallbackPlan.requestedContainers().size() - 1).id());
		PackagerResult fallbackResult = packWholeOrders(fallbackPlan, true, targetContainerCount);
		boolean achieved = fallbackResult != null && fallbackResult.isSuccess()
				&& fallbackResult.size() <= targetContainerCount;
		System.out.println("packing-service automatic-fallback success=" + achieved
				+ " baselineSuccess=" + (fallbackResult != null && fallbackResult.isSuccess())
				+ " finalContainers=" + (fallbackResult == null ? 0 : fallbackResult.size())
				+ " targetContainers=" + targetContainerCount);
		return new PackingOutcome(fallbackPlan, fallbackResult, achieved, true, targetContainerCount);
	}

	static PackingPlan withAutomatic40Hq(PackingPlan plan) {
		Set<String> ids = new HashSet<>();
		for (ContainerDto container : plan.requestedContainers()) ids.add(container.id());
		int targetContainerCount = totalContainerCount(plan.containerItems());
		String baseId = "AUTO-40HQ-" + (targetContainerCount + 1);
		String id = baseId;
		for (int suffix = 2; ids.contains(id); suffix++) id = baseId + "-" + suffix;
		ContainerDto automatic = new ContainerDto(id, 40, "HQ");
		ContainerItem mapped = PackingMapper.toContainerItem(automatic);
		if (mapped == null) throw new IllegalStateException("无法创建自动40HQ柜型");

		List<ContainerDto> requested = new ArrayList<>(plan.requestedContainers());
		requested.add(automatic);
		List<ContainerItem> containers = new ArrayList<>(plan.containerItems());
		containers.add(mapped);
		List<String> warnings = new ArrayList<>(plan.warnings());
		warnings.add("AUTO_40HQ_FALLBACK targetContainers=" + targetContainerCount
				+ " addedContainerId=" + id);
		return new PackingPlan(List.copyOf(requested), List.copyOf(containers), plan.boxItems(),
				plan.cargoLines(), List.copyOf(warnings));
	}

	private PackagerResult packWholeOrders(PackingPlan plan, boolean compactTail, int compactionTargetCount) {
		long searchStarted = System.nanoTime();
		long deadline = System.currentTimeMillis() + WHOLE_ORDER_SEARCH_MILLIS;
		long searchId = SEARCH_SEQUENCE.incrementAndGet();
		List<ContainerItem> physicalContainers = expandContainerItems(plan.containerItems());
		long candidateGenerationStarted = System.nanoTime();
		List<WholeOrderAssignmentSolver.Candidate> candidates = assignmentSolver.candidates(
				plan, assignmentStrategy, assignmentTargetFillRatio);
		long candidateGenerationMillis = elapsedMillis(candidateGenerationStarted);
		WholeOrderPackingCache cache = new WholeOrderPackingCache();
		System.out.println("packing-service whole-order search-start searchId=" + searchId
				+ " candidates=" + candidates.size() + " candidateGenerationMs=" + candidateGenerationMillis
				+ " containers=" + physicalContainers.size() + " houseBills=" + houseBillCount(plan.boxItems())
				+ " itemTypes=" + plan.boxItems().size() + " units=" + unitCount(plan.boxItems())
				+ " deadlineMs=" + WHOLE_ORDER_SEARCH_MILLIS
				+ " assignmentStrategy=" + assignmentStrategy.propertyValue()
				+ " targetFillRatio=" + assignmentTargetFillRatio
				+ " parallelism=" + wholeOrderParallelism);
		PackagerResult best = null;
		int attempted = 0;
		int successful = 0;
		int workerCount = Math.min(wholeOrderParallelism, Math.max(1, candidates.size()));
		AtomicLong workerSequence = new AtomicLong();
		ExecutorService executor = Executors.newFixedThreadPool(workerCount, runnable -> {
			Thread thread = new Thread(runnable,
					"packing-candidate-" + searchId + '-' + workerSequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		try {
			search:
			for (int batchStart = 0; batchStart < candidates.size()
					&& System.currentTimeMillis() < deadline; batchStart += workerCount) {
				List<Future<CandidateValidation>> futures = new ArrayList<>();
				int batchEnd = Math.min(candidates.size(), batchStart + workerCount);
				for (int candidateIndex = batchStart; candidateIndex < batchEnd; candidateIndex++) {
					WholeOrderAssignmentSolver.Candidate candidate = candidates.get(candidateIndex);
					int attempt = candidateIndex + 1;
					futures.add(executor.submit(() -> validateCandidate(plan, physicalContainers,
							candidate, attempt, searchId, deadline, cache)));
					attempted++;
				}
				for (int futureIndex = 0; futureIndex < futures.size(); futureIndex++) {
					long remainingMillis = deadline - System.currentTimeMillis();
					if (remainingMillis <= 0L) break search;
					try {
						CandidateValidation validation = futures.get(futureIndex).get(
								remainingMillis, TimeUnit.MILLISECONDS);
						if (!validation.success()) continue;
						successful++;
						best = validation.result();
						for (int cancel = futureIndex + 1; cancel < futures.size(); cancel++) {
							futures.get(cancel).cancel(true);
						}
						break search;
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break search;
					} catch (TimeoutException e) {
						break search;
					} catch (ExecutionException e) {
						System.err.println("packing-service whole-order candidate-error searchId="
								+ searchId + " attempt=" + (batchStart + futureIndex + 1)
								+ " reason=" + e.getCause());
					}
				}
			}
		} finally {
			executor.shutdownNow();
		}
		WholeOrderPackingCache.Stats cacheStats = cache.stats();
		System.out.println("packing-service whole-order candidates=" + candidates.size()
				+ " searchId=" + searchId + " attempted=" + attempted + " successful=" + successful
				+ " success=" + (best != null) + " candidateGenerationMs=" + candidateGenerationMillis
				+ " elapsedMs=" + elapsedMillis(searchStarted)
				+ " cacheHits=" + cacheStats.hits() + " cacheMisses=" + cacheStats.misses()
				+ " cacheSuccessHits=" + cacheStats.successHits()
				+ " cacheFailureHits=" + cacheStats.failureHits() + " cacheSize=" + cacheStats.size()
				+ " cacheSavedMs=" + cacheStats.savedMillis()
				+ " termination=" + terminationReason(candidates.size(), attempted, successful, deadline));
		if (shouldCompactTail(compactTail, tailCompactionEnabled, best, compactionTargetCount)) {
			long compactionDeadline = System.currentTimeMillis() + tailCompactionSearchMillis;
			PackagerResult baseline = best;
			try {
				best = new TailContainerCompactor(supportPolicy,
						tailCompactionBeamWidth, tailCompactionBranching).compact(best, compactionDeadline);
			} catch (RuntimeException e) {
				System.err.println("packing-service tail-compaction failed unexpectedly; keeping baseline: "
						+ e.getMessage());
				e.printStackTrace(System.err);
				best = baseline;
			}
		}
		return best;
	}

	private CandidateValidation validateCandidate(PackingPlan plan, List<ContainerItem> physicalContainers,
			WholeOrderAssignmentSolver.Candidate candidate, int attempt, long searchId, long deadline,
			WholeOrderPackingCache cache) {
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
			LogContext context = new LogContext(searchId, attempt, candidate.source(),
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
				packed = packFlat(containerPlan, deadline, context);
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
		return new CandidateValidation(success, result);
	}

	static boolean shouldCompactTail(boolean compactTail, boolean tailCompactionEnabled,
			PackagerResult result, int targetContainerCount) {
		return compactTail && tailCompactionEnabled && result != null && result.isSuccess()
				&& targetContainerCount > 0 && result.size() > targetContainerCount;
	}

	private static List<ContainerItem> expandContainerItems(List<ContainerItem> items) {
		List<ContainerItem> result = new ArrayList<>();
		for (ContainerItem item : items) {
			for (int i = 0; i < item.getCount(); i++) {
				result.add(new ContainerItem(item.getContainer(), 1));
			}
		}
		return result;
	}

	private static boolean splitsHouseBills(PackagerResult result) {
		Map<String, Integer> containersByHouseBill = new HashMap<>();
		for (int containerIndex = 0; containerIndex < result.getContainers().size(); containerIndex++) {
			Container container = result.getContainers().get(containerIndex);
			for (com.github.skjolber.packing.api.Placement placement : container.getStack().getPlacements()) {
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				if (houseBsId == null) {
					continue;
				}
				Integer previous = containersByHouseBill.putIfAbsent(houseBsId, containerIndex);
				if (previous != null && previous != containerIndex) {
					return true;
				}
			}
		}
		return false;
	}

	private PackagerResult packFlat(PackingPlan plan, long deadline) {
		return packFlat(plan, deadline, LogContext.NONE);
	}

	private PackagerResult packFlat(PackingPlan plan, long deadline, LogContext context) {
		PackagerResult best = null;
		best = better(best, packOrientation(plan, false, deadline, context));
		if (best != null && best.isSuccess() && best.size() == 1) {
			return best;
		}
		best = better(best, packOrientation(plan, true, deadline, context));
		return best;
	}

	private PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth, long deadline,
			LogContext context) {
		List<ContainerItem> containers = swapLengthWidth ? swappedContainers(plan.containerItems()) : plan.containerItems();
		PackagerResult best = null;
		PackagerResult blockBeam = tryBlockBeamSearch(plan, containers,
				"BlockBeam" + (swapLengthWidth ? "-SWAPPED" : ""), deadline, context);
		if (blockBeam != null && blockBeam.isSuccess()) {
			return blockBeam;
		}
		if (blockBeamOnly) {
			return null;
		}
		if (hasBusinessRule(plan)) {
			boolean hasDoorSideRule = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			best = better(best, tryMacroPacks(plan, containers,
					"Plain-Rules-MACRO-Y", swapLengthWidth,
					hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
					hasDoorSideRule, deadline, context));
			best = better(best, tryPack(plan, containers, "Plain-Rules-LAYOUT" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new LayoutPlacementComparator(),
							hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
							supportPolicy,
							hasDoorSideRule,
							true))
					.build(), deadline, context));
			best = better(best, tryPack(plan, containers, "Plain-Rules" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new PlainPlacementComparator(),
							hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
							supportPolicy,
							hasDoorSideRule))
					.build(), deadline, context));
			return best;
		}
		best = better(best, tryMacroPacks(plan, containers, "Plain-MACRO-Y", swapLengthWidth,
				VolumeThenWeightBoxItemComparator.getInstance(), false, deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-LAYOUT" + (swapLengthWidth ? "-SWAPPED" : ""),
				layoutPlain(VolumeThenWeightBoxItemComparator.getInstance()), deadline, context));
		best = better(best, tryPack(plan, containers, "LAFF" + (swapLengthWidth ? "-SWAPPED" : ""), LargestAreaFitFirstPackager.newBuilder().build(), deadline, context));
		best = better(best, tryPack(plan, containers, "FastLAFF" + (swapLengthWidth ? "-SWAPPED" : ""), FastLargestAreaFitFirstPackager.newBuilder().build(), deadline, context));
		String suffix = swapLengthWidth ? "-SWAPPED" : "";
		best = better(best, tryPack(plan, containers, "Plain-STABLE" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), new PlainPlacementComparator()), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-AREA" + suffix,
				stablePlain(new LargestAreaBoxItemComparator(), new PlainPlacementComparator()), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-Y" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(true)), deadline, context));
		best = better(best, tryPack(plan, containers, "Plain-STABLE-FLOOR-X" + suffix,
				stablePlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(false)), deadline, context));
		return best;
	}

	private PackagerResult tryBlockBeamSearch(PackingPlan plan, List<ContainerItem> containers,
			String label, long deadline, LogContext context) {
		if (containers.size() != 1 || totalContainerCount(containers) != 1) return null;
		long started = System.nanoTime();
		long searchDeadline = System.currentTimeMillis() + BLOCK_BEAM_SEARCH_MILLIS;
		if (deadline > 0L) searchDeadline = Math.min(searchDeadline, deadline);
		List<BlockBeamAttempt> quickAttempts = new ArrayList<>();
		PackagerResult result = null;
		BlockBeamSearchPackager.SearchProgress bestProgress = null;
		boolean wholeOrderValidation = context.searchId() != 0L;
		boolean allowLns = !wholeOrderValidation && !hasBusinessRule(plan);
		long explorationDeadline = allowLns
				? Math.max(System.currentTimeMillis(), searchDeadline - 8_000L)
				: searchDeadline;

		if (wholeOrderValidation) {
			BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
					64, 48, supportPolicy, BlockBeamSearchPackager.SearchProfile.BALANCED);
			BlockBeamSearchPackager.SearchSession session = packager.newSession(
					containers.get(0).getContainer(), plan.boxItems(),
					context.fields() + " effort=baseline profile=balanced");
			if (session != null) {
				BlockBeamSearchPackager.SearchProgress progress = session.advance(explorationDeadline);
				quickAttempts.add(new BlockBeamAttempt(
						BlockBeamSearchPackager.SearchProfile.BALANCED, progress, session.bestPartial()));
				bestProgress = progress;
				if (progress.complete()) result = progress.result();
			}
		}

		if (!wholeOrderValidation) {
			for (BlockBeamSearchPackager.SearchProfile profile : BlockBeamSearchPackager.SearchProfile.values()) {
				if (result != null) break;
				if (System.currentTimeMillis() >= explorationDeadline) break;
				BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
						64, 48, supportPolicy, profile);
				BlockBeamSearchPackager.SearchSession session = packager.newSession(
						containers.get(0).getContainer(), plan.boxItems(),
						context.fields() + " effort=quick profile=" + profile.propertyValue());
				if (session == null) continue;
				long quickDeadline = Math.min(explorationDeadline,
						System.currentTimeMillis() + BLOCK_BEAM_QUICK_SEARCH_MILLIS);
				BlockBeamSearchPackager.SearchProgress progress = session.advance(quickDeadline);
				quickAttempts.add(new BlockBeamAttempt(profile, progress, session.bestPartial()));
				bestProgress = betterProgress(bestProgress, progress);
				if (progress.complete()) {
					result = progress.result();
					break;
				}
			}
		}

		// Whole-order validation must try both the original and swapped container
		// orientations within the shared 180-second budget. Restarting the same
		// Balanced profile with a wider beam after it is exhausted can consume the
		// budget before the swapped orientation or next assignment is reached.
		if (!wholeOrderValidation && result == null && System.currentTimeMillis() < explorationDeadline) {
			quickAttempts.sort(Comparator
					.comparingDouble((BlockBeamAttempt attempt) ->
							attempt.progress().volumeCompletionRatio()).reversed()
					.thenComparing(Comparator.comparingDouble((BlockBeamAttempt attempt) ->
							attempt.progress().completionRatio()).reversed())
					.thenComparing(Comparator.comparingInt((BlockBeamAttempt attempt) ->
							attempt.progress().eliteStates()).reversed()));
			List<BlockBeamAttempt> intensiveAttempts = new ArrayList<>();
			if (!wholeOrderValidation) {
				quickAttempts.stream()
						.filter(attempt -> attempt.profile() == BlockBeamSearchPackager.SearchProfile.BALANCED)
						.findFirst().ifPresent(intensiveAttempts::add);
			}
			for (BlockBeamAttempt attempt : quickAttempts) {
				if (intensiveAttempts.size() >= BLOCK_BEAM_INTENSIVE_PROFILES) break;
				if (!intensiveAttempts.contains(attempt)) intensiveAttempts.add(attempt);
			}
			int intensiveCount = intensiveAttempts.size();
			for (int index = 0; index < intensiveCount && System.currentTimeMillis() < explorationDeadline; index++) {
				long remaining = explorationDeadline - System.currentTimeMillis();
				int remainingProfiles = intensiveCount - index;
				long share = index == 0 && remainingProfiles > 1
						? Math.max(1L, Math.round(remaining * 0.7))
						: Math.max(1L, remaining / remainingProfiles);
				BlockBeamSearchPackager.SearchProfile profile = intensiveAttempts.get(index).profile();
				BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
						BLOCK_BEAM_INTENSIVE_WIDTH, BLOCK_BEAM_INTENSIVE_BRANCHING,
						supportPolicy, profile);
				BlockBeamSearchPackager.SearchSession session = packager.newSession(
						containers.get(0).getContainer(), plan.boxItems(),
						context.fields() + " effort=intensive profile=" + profile.propertyValue());
				if (session == null) continue;
				BlockBeamSearchPackager.SearchProgress progress = session.advance(
						Math.min(explorationDeadline, System.currentTimeMillis() + share));
				quickAttempts.add(new BlockBeamAttempt(profile, progress, session.bestPartial()));
				bestProgress = betterProgress(bestProgress, progress);
				if (progress.complete()) {
					result = progress.result();
					break;
				}
			}
		}
		if (result == null && allowLns && System.currentTimeMillis() < searchDeadline) {
			BlockBeamSearchPackager.PartialSolution partial = quickAttempts.stream()
					.map(BlockBeamAttempt::partial)
					.filter(java.util.Objects::nonNull)
					.max(Comparator.comparingDouble(
							BlockBeamSearchPackager.PartialSolution::volumeCompletionRatio))
					.orElse(null);
			if (partial != null) {
				result = new ExtremePointGapFiller(supportPolicy).fill(
						partial, searchDeadline, context.fields());
				if (result == null && System.currentTimeMillis() < searchDeadline) {
					result = new BlockBeamLargeNeighborhoodSearch(supportPolicy)
							.repair(partial, searchDeadline, context.fields());
				}
			}
		}
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
		if (result == null || !result.isSuccess()) {
			System.out.println("packing-service " + label + context.fields()
					+ " success=false elapsedMs=" + elapsedMillis
					+ " reason=" + (System.currentTimeMillis() >= searchDeadline ? "deadline" : "search-exhausted")
					+ " quickProfiles=" + quickAttempts.size()
					+ " completion=" + rounded(bestProgress == null ? 0.0 : bestProgress.completionRatio())
					+ " volumeCompletion=" + rounded(bestProgress == null ? 0.0
							: bestProgress.volumeCompletionRatio()));
			return null;
		}
		PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
		boolean valid = support.valid() && BottomRuleSupport.isValid(result)
				&& NoPressRuleSupport.isValid(result);
		if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
			DoorSideRuleSupport.mirrorToDoorSide(result);
			valid = DoorSideRuleSupport.isValid(result);
		}
		System.out.println("packing-service " + label + context.fields() + " success=" + valid
				+ " placements=" + result.get(0).getStack().size() + " elapsedMs=" + elapsedMillis
				+ (support.valid() ? "" : " rejectedBox=" + support.boxId()
						+ " reason=" + support.reason() + " ratio=" + support.supportRatio()));
		return valid ? result : null;
	}

	private static BlockBeamSearchPackager.SearchProgress betterProgress(
			BlockBeamSearchPackager.SearchProgress current,
			BlockBeamSearchPackager.SearchProgress candidate) {
		if (current == null) return candidate;
		int volume = Double.compare(candidate.volumeCompletionRatio(), current.volumeCompletionRatio());
		if (volume != 0) return volume > 0 ? candidate : current;
		int units = Double.compare(candidate.completionRatio(), current.completionRatio());
		if (units != 0) return units > 0 ? candidate : current;
		return candidate.eliteStates() > current.eliteStates() ? candidate : current;
	}

	private record BlockBeamAttempt(BlockBeamSearchPackager.SearchProfile profile,
			BlockBeamSearchPackager.SearchProgress progress,
			BlockBeamSearchPackager.PartialSolution partial) {
	}

	private record CandidateValidation(boolean success, PackagerResult result) {
	}

	private PackagerResult tryMacroPacks(PackingPlan plan, List<ContainerItem> containers,
			String label, boolean swapLengthWidth, Comparator<BoxItem> boxComparator,
			boolean preferDoorSidePlacements, long deadline, LogContext context) {
		if (containers.size() != 1 || totalContainerCount(containers) != 1) {
			return null;
		}
		PackagerResult best = null;
		Container container = containers.get(0).getContainer();
		int[][] cuboids = {{2, 2, 5}, {1, 4, 5}, {1, 2, 5}, {1, 1, 5}};
		int[] typeLimits = {Integer.MAX_VALUE, 4};
		for (int[] cuboid : cuboids) {
			for (int typeLimit : typeLimits) {
				MacroBlockPlanner.Plan macroPlan = MacroBlockPlanner.cuboid(plan.boxItems(), container,
						cuboid[0], cuboid[1], cuboid[2], typeLimit);
				best = better(best, tryMacroPack(plan, containers,
						label + "C" + cuboid[0] + "x" + cuboid[1] + "x" + cuboid[2]
								+ "T" + macroTypeLabel(typeLimit) + (swapLengthWidth ? "-SWAPPED" : ""),
						boxComparator, preferDoorSidePlacements, deadline, macroPlan, context));
			}
		}
		int[][] grids = {{2, 2}, {1, 4}, {1, 2}};
		for (int[] grid : grids) {
			MacroBlockPlanner.Plan macroPlan = MacroBlockPlanner.grid(
					plan.boxItems(), container, grid[0], grid[1]);
			best = better(best, tryMacroPack(plan, containers,
					label + "G" + grid[0] + "x" + grid[1] + (swapLengthWidth ? "-SWAPPED" : ""),
					boxComparator, preferDoorSidePlacements, deadline, macroPlan, context));
		}
		MacroBlockPlanner.Plan fullRows = MacroBlockPlanner.rowsAcrossY(
				plan.boxItems(), container, Integer.MAX_VALUE);
		best = better(best, tryMacroPack(plan, containers,
				label + "FULL" + (swapLengthWidth ? "-SWAPPED" : ""),
				boxComparator, preferDoorSidePlacements, deadline, fullRows, context));
		return best;
	}

	private static String macroTypeLabel(int maximumMacroTypes) {
		return maximumMacroTypes == Integer.MAX_VALUE ? "ALL" : Integer.toString(maximumMacroTypes);
	}

	private PackagerResult tryMacroPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Comparator<BoxItem> boxComparator, boolean preferDoorSidePlacements, long deadline,
			MacroBlockPlanner.Plan macroPlan, LogContext context) {
		if (!macroPlan.hasMacros()) {
			return null;
		}

		PlainPackager packager = PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						new LayoutPlacementComparator(), boxComparator, supportPolicy,
						preferDoorSidePlacements, true))
				.build();
		long started = System.nanoTime();
		PackingSearchDiagnostics.begin(diagnosticsEnabled);
		try {
			AbstractPackagerResultBuilder<?> builder = packager.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(1)
					.withBoxItems(cloneBoxItems(macroPlan.boxItems()));
			if (deadline > 0L) {
				builder.withDeadline(deadline);
			}
			PackagerResult packed = builder.build();
			PackagerResult result = packed.isSuccess() ? macroPlan.expand(packed) : packed;
			long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
			PackingSearchDiagnostics.Counters diagnostics = PackingSearchDiagnostics.finish();
			if (!result.isSuccess()) {
				System.out.println("packing-service " + label + context.fields() + " success=false"
						+ " macroTypes=" + macroPlan.blocks().size() + " placements=0"
						+ " elapsedMs=" + elapsedMillis + " candidates=" + diagnostics.candidates()
						+ " rejected=" + diagnostics.rejections() + " reason=" + deadlineReason(deadline));
				return null;
			}
			PlacementSupport.Validation support = PlacementSupport.validate(result, supportPolicy);
			boolean bottomValid = BottomRuleSupport.isValid(result);
			boolean noPressValid = NoPressRuleSupport.isValid(result);
			boolean valid = support.valid() && bottomValid && noPressValid;
			boolean doorValid = true;
			if (valid && DoorSideRuleSupport.hasDoorSideRule(plan.boxItems())) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				doorValid = DoorSideRuleSupport.isValid(result);
				valid = doorValid;
			}
			System.out.println("packing-service " + label + context.fields() + " success=" + valid
					+ " macroTypes=" + macroPlan.blocks().size()
					+ " placements=" + result.get(0).getStack().size()
					+ " elapsedMs=" + elapsedMillis + " candidates=" + diagnostics.candidates()
					+ " rejected=" + diagnostics.rejections()
					+ " reason=" + validationReason(support, bottomValid, noPressValid, doorValid));
			if (!valid && !support.valid()) {
				System.out.println("packing-service " + label + context.fields() + " expanded-result rejected box="
						+ support.boxId() + " reason=" + support.reason()
						+ " ratio=" + support.supportRatio());
			}
			return valid ? result : null;
		} finally {
			PackingSearchDiagnostics.finish();
			try {
				packager.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private PlainPackager layoutPlain(Comparator<BoxItem> boxComparator) {
		return PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						new LayoutPlacementComparator(), boxComparator, supportPolicy, false, true))
				.build();
	}

	private PlainPackager stablePlain(Comparator<BoxItem> boxComparator,
			Comparator<Placement> placementComparator) {
		return PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						placementComparator, boxComparator, supportPolicy))
				.build();
	}

	private static Comparator<Placement> floorFirst(boolean yBeforeX) {
		Comparator<Placement> lowerZ = Comparator.comparingInt(Placement::getAbsoluteZ).reversed();
		Comparator<Placement> lowerX = Comparator.comparingInt(Placement::getAbsoluteX).reversed();
		Comparator<Placement> lowerY = Comparator.comparingInt(Placement::getAbsoluteY).reversed();
		Comparator<Placement> position = yBeforeX
				? lowerZ.thenComparing(lowerY).thenComparing(lowerX)
				: lowerZ.thenComparing(lowerX).thenComparing(lowerY);
		return position.thenComparingLong(placement -> placement.getStackValue().getArea());
	}

	private PackagerResult tryPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Packager<? extends AbstractPackagerResultBuilder<?>> packager, long deadline,
			LogContext context) {
		long started = System.nanoTime();
		PackingSearchDiagnostics.begin(diagnosticsEnabled);
		try {
			AbstractPackagerResultBuilder<?> builder = packager
					.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(totalContainerCount(containers))
					.withBoxItems(cloneBoxItems(plan.boxItems()));
			if (deadline > 0L) {
				builder.withDeadline(deadline);
			}
			PackagerResult result = builder.build();

			long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
			PackingSearchDiagnostics.Counters diagnostics = PackingSearchDiagnostics.finish();
			int placements = result.getContainers().stream()
					.mapToInt(container -> container.getStack().getPlacements().size()).sum();
			PlacementSupport.Validation support = result.isSuccess()
					? PlacementSupport.validate(result, supportPolicy)
					: PlacementSupport.Validation.supported();
			if (!support.valid()) {
				System.out.println("packing-service " + label + context.fields()
						+ " rejected unsupported box=" + support.boxId()
						+ " position=" + support.x() + "," + support.y() + "," + support.z()
						+ " ratio=" + support.supportRatio() + " reason=" + support.reason());
			}
			boolean bottomValid = result.isSuccess() && BottomRuleSupport.isValid(result);
			boolean noPressValid = result.isSuccess() && NoPressRuleSupport.isValid(result);
			boolean valid = result.isSuccess() && support.valid() && bottomValid && noPressValid;
			boolean hasDoorSideRule = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			boolean doorValid = true;
			if (valid && hasDoorSideRule) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				doorValid = DoorSideRuleSupport.isValid(result);
				valid = doorValid;
			}
			String reason = result.isSuccess()
					? validationReason(support, bottomValid, noPressValid, doorValid)
					: deadlineReason(deadline);
			System.out.println("packing-service " + label + context.fields() + " success=" + valid
					+ " packed=" + result.isSuccess() + " containerCount=" + result.size()
					+ " placements=" + placements + " elapsedMs=" + elapsedMillis
					+ " candidates=" + diagnostics.candidates() + " rejected=" + diagnostics.rejections()
					+ " reason=" + reason);
			return valid ? result : null;
		} finally {
			PackingSearchDiagnostics.finish();
			try {
				packager.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static boolean hasBusinessRule(PackingPlan plan) {
		return plan.boxItems().stream().anyMatch(item ->
				BottomRuleSupport.hasBottomRule(item.getBox())
						|| NoPressRuleSupport.hasNoPressRule(item.getBox())
						|| DoorSideRuleSupport.hasDoorSideRule(item.getBox()));
	}

	private static List<ContainerItem> swappedContainers(List<ContainerItem> source) {
		return source.stream().map(item -> {
			Container original = item.getContainer();
			Container swapped = Container.newBuilder()
					.withId(original.getId())
					.withDescription(original.getDescription() + "-SWAPPED")
					.withSize(original.getDy(), original.getDx(), original.getDz())
					.withEmptyWeight(original.getEmptyWeight())
					.withMaxLoadWeight(original.getMaxLoadWeight())
					.build();
			return new ContainerItem(swapped, item.getCount());
		}).toList();
	}

	private static PackagerResult better(PackagerResult current, PackagerResult candidate) {
		if (candidate == null) {
			return current;
		}
		if (current == null || candidate.size() < current.size()) {
			return candidate;
		}
		if (candidate.size() == current.size()
				&& totalContainerCapacity(candidate) < totalContainerCapacity(current)) {
			return candidate;
		}
		if (candidate.size() == current.size() && totalLoadVolume(candidate) > totalLoadVolume(current)) {
			return candidate;
		}
		if (candidate.size() == current.size() && totalLoadVolume(candidate) == totalLoadVolume(current)
				&& DoorSideRuleSupport.score(candidate).isBetterThan(DoorSideRuleSupport.score(current))) {
			return candidate;
		}
		return current;
	}

	private static int totalContainerCount(List<ContainerItem> containerItems) {
		int count = 0;
		for (ContainerItem item : containerItems) {
			count += item.getCount();
		}
		return count;
	}

	private static long totalLoadVolume(PackagerResult result) {
		return result.getContainers().stream().mapToLong(c -> c.getLoadVolume()).sum();
	}

	private static long totalContainerCapacity(PackagerResult result) {
		return result.getContainers().stream().mapToLong(Container::getMaxLoadVolume).sum();
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	private static int unitCount(List<BoxItem> items) {
		return items.stream().mapToInt(BoxItem::getCount).sum();
	}

	private static long totalVolume(List<BoxItem> items) {
		return items.stream().mapToLong(BoxItem::getVolume).sum();
	}

	private static long totalWeight(List<BoxItem> items) {
		return items.stream().mapToLong(BoxItem::getWeight).sum();
	}

	private static int houseBillCount(List<BoxItem> items) {
		Set<String> ids = new HashSet<>();
		for (BoxItem item : items) {
			String id = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			ids.add(id == null || id.isBlank() ? "<anonymous>" : id);
		}
		return ids.size();
	}

	private static String houseBillIds(List<BoxItem> items) {
		Set<String> ids = new HashSet<>();
		for (BoxItem item : items) {
			String id = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			ids.add(safe(id == null || id.isBlank() ? "<anonymous>" : id));
		}
		return String.join(",", ids.stream().sorted().toList());
	}

	private static double percent(long value, long maximum) {
		if (maximum <= 0L) return 0.0;
		return Math.round(value * 10_000.0 / maximum) / 100.0;
	}

	private static double rounded(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	private static String safe(String value) {
		if (value == null || value.isBlank()) return "unknown";
		return value.replaceAll("\\s+", "_");
	}

	private static String deadlineReason(long deadline) {
		return deadline > 0L && System.currentTimeMillis() >= deadline ? "deadline" : "no-placement";
	}

	private static String validationReason(PlacementSupport.Validation support,
			boolean bottomValid, boolean noPressValid, boolean doorValid) {
		if (!support.valid()) return "support-" + safe(support.reason());
		if (!bottomValid) return "bottom-rule";
		if (!noPressValid) return "no-press-rule";
		if (!doorValid) return "door-side-rule";
		return "none";
	}

	private static String terminationReason(int candidates, int attempted, int successful, long deadline) {
		if (System.currentTimeMillis() >= deadline) return "deadline";
		if (successful > 0 && attempted < candidates) return "first-success";
		if (attempted >= candidates) return "candidates-exhausted";
		return "stopped";
	}

	private record LogContext(long searchId, int attempt, String source,
			int containerIndex, int containerCount, String containerId) {
		private static final LogContext NONE = new LogContext(0L, 0, "", 0, 0, "");

		private String fields() {
			if (searchId == 0L) return "";
			return " searchId=" + searchId + " attempt=" + attempt + " source=" + safe(source)
					+ " container=" + containerIndex + "/" + containerCount
					+ " containerId=" + safe(containerId);
		}
	}

	private static List<com.github.skjolber.packing.api.BoxItem> cloneBoxItems(List<com.github.skjolber.packing.api.BoxItem> items) {
		return items.stream().map(com.github.skjolber.packing.api.BoxItem::clone).toList();
	}
}
