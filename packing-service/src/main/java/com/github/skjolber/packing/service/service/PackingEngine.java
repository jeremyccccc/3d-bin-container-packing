package com.github.skjolber.packing.service.service;

import static com.github.skjolber.packing.service.service.PackingDiagnostics.elapsedMillis;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.houseBillCount;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.safe;
import static com.github.skjolber.packing.service.service.PackingDiagnostics.unitCount;

import java.util.ArrayList;
import java.util.List;
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

import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;

@Component
class PackingEngine {
	private static final long WHOLE_ORDER_SEARCH_MILLIS = 180_000L;
	private static final int DEFAULT_CANDIDATE_PROBE_WINDOW = 8;
	private static final int DEFAULT_CANDIDATE_PROBE_STATES = 8;
	private static final AtomicLong SEARCH_SEQUENCE = new AtomicLong();

	private final WholeOrderAssignmentSolver assignmentSolver = new WholeOrderAssignmentSolver();
	private final WholeOrderCandidateSearch candidateSearch;
	private final WholeOrderCandidateValidator candidateValidator;
	private final BlockBeamPackingStrategy blockBeamStrategy;
	private final LegacyContainerPackingStrategy legacyStrategy;
	private final PlacementSupport.Policy supportPolicy;
	private final PackingEngineSettings settings;

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
			@Value("${packing.whole-order.parallelism:1}") int wholeOrderParallelism,
			@Value("${packing.whole-order.candidate-probe-enabled:false}") boolean candidateProbeEnabled,
			@Value("${packing.whole-order.candidate-probe-window:8}") int candidateProbeWindow,
			@Value("${packing.whole-order.candidate-probe-states:8}") int candidateProbeStates) {
		this(new PlacementSupport.Policy(minimumAreaRatio, requireCenterSupport,
				maximumOverhangMillimeters, maximumOverhangRatio), diagnosticsEnabled, blockBeamOnly,
				WholeOrderAssignmentStrategy.parse(assignmentStrategy), assignmentTargetFillRatio,
				tailCompactionEnabled, tailCompactionSearchMillis,
				tailCompactionBeamWidth, tailCompactionBranching, wholeOrderParallelism,
				candidateProbeEnabled, candidateProbeWindow, candidateProbeStates);
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
		this(supportPolicy, diagnosticsEnabled, blockBeamOnly, assignmentStrategy, assignmentTargetFillRatio,
				tailCompactionEnabled, tailCompactionSearchMillis, tailCompactionBeamWidth,
				tailCompactionBranching, wholeOrderParallelism, false,
				DEFAULT_CANDIDATE_PROBE_WINDOW, DEFAULT_CANDIDATE_PROBE_STATES);
	}

	PackingEngine(PlacementSupport.Policy supportPolicy, boolean diagnosticsEnabled, boolean blockBeamOnly,
			WholeOrderAssignmentStrategy assignmentStrategy, double assignmentTargetFillRatio,
			boolean tailCompactionEnabled, long tailCompactionSearchMillis,
			int tailCompactionBeamWidth, int tailCompactionBranching, int wholeOrderParallelism,
			boolean candidateProbeEnabled, int candidateProbeWindow, int candidateProbeStates) {
		this(supportPolicy, new PackingEngineSettings(diagnosticsEnabled, blockBeamOnly,
				assignmentStrategy, assignmentTargetFillRatio, tailCompactionEnabled,
				tailCompactionSearchMillis, tailCompactionBeamWidth, tailCompactionBranching,
				wholeOrderParallelism, candidateProbeEnabled, candidateProbeWindow, candidateProbeStates));
	}

	private PackingEngine(PlacementSupport.Policy supportPolicy, PackingEngineSettings settings) {
		this.supportPolicy = supportPolicy;
		this.settings = settings;
		this.candidateSearch = new WholeOrderCandidateSearch(supportPolicy,
				settings.candidateProbeEnabled(), settings.candidateProbeWindow(), settings.candidateProbeStates());
		this.candidateValidator = new WholeOrderCandidateValidator(settings.diagnosticsEnabled());
		this.blockBeamStrategy = new BlockBeamPackingStrategy(supportPolicy);
		this.legacyStrategy = new LegacyContainerPackingStrategy(supportPolicy, settings.diagnosticsEnabled());
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

		PackingPlan fallbackPlan = AutomaticContainerFallback.add40Hq(plan);
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

	private PackagerResult packWholeOrders(PackingPlan plan, boolean compactTail, int compactionTargetCount) {
		long searchStarted = System.nanoTime();
		long deadline = System.currentTimeMillis() + WHOLE_ORDER_SEARCH_MILLIS;
		long searchId = SEARCH_SEQUENCE.incrementAndGet();
		List<ContainerItem> physicalContainers = expandContainerItems(plan.containerItems());
		long candidateGenerationStarted = System.nanoTime();
		List<WholeOrderAssignmentSolver.Candidate> candidates = assignmentSolver.candidates(
				plan, settings.assignmentStrategy(), settings.assignmentTargetFillRatio());
		long candidateGenerationMillis = elapsedMillis(candidateGenerationStarted);
		WholeOrderPackingCache cache = new WholeOrderPackingCache();
		System.out.println("packing-service whole-order search-start searchId=" + searchId
				+ " candidates=" + candidates.size() + " candidateGenerationMs=" + candidateGenerationMillis
				+ " containers=" + physicalContainers.size() + " houseBills=" + houseBillCount(plan.boxItems())
				+ " itemTypes=" + plan.boxItems().size() + " units=" + unitCount(plan.boxItems())
				+ " deadlineMs=" + WHOLE_ORDER_SEARCH_MILLIS
				+ " assignmentStrategy=" + settings.assignmentStrategy().propertyValue()
				+ " targetFillRatio=" + settings.assignmentTargetFillRatio()
				+ " parallelism=" + settings.wholeOrderParallelism());
		PackagerResult best = null;
		int attempted = 0;
		int successful = 0;
		int workerCount = Math.min(settings.wholeOrderParallelism(), Math.max(1, candidates.size()));
		if (workerCount == 1) {
			WholeOrderCandidateSearch.Result searchResult = candidateSearch.search(
					candidates, physicalContainers, cache, searchId, deadline,
					(candidate, attempt) -> {
						WholeOrderCandidateValidator.Result validation = candidateValidator.validate(
								plan, physicalContainers, candidate, attempt, searchId, deadline,
								cache, this::packFlat);
						return validation.success() ? validation.result() : null;
					});
			best = searchResult.result();
			attempted = searchResult.attempted();
			successful = best == null ? 0 : 1;
		} else {
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
					List<Future<WholeOrderCandidateValidator.Result>> futures = new ArrayList<>();
					int batchEnd = Math.min(candidates.size(), batchStart + workerCount);
					for (int candidateIndex = batchStart; candidateIndex < batchEnd; candidateIndex++) {
						WholeOrderAssignmentSolver.Candidate candidate = candidates.get(candidateIndex);
						int attempt = candidateIndex + 1;
						futures.add(executor.submit(() -> candidateValidator.validate(
								plan, physicalContainers, candidate, attempt, searchId, deadline,
								cache, this::packFlat)));
						attempted++;
					}
					for (int futureIndex = 0; futureIndex < futures.size(); futureIndex++) {
						long remainingMillis = deadline - System.currentTimeMillis();
						if (remainingMillis <= 0L) break search;
						try {
							WholeOrderCandidateValidator.Result validation = futures.get(futureIndex).get(
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
		if (AutomaticContainerFallback.shouldCompact(
				compactTail, settings.tailCompactionEnabled(), best, compactionTargetCount)) {
			long compactionDeadline = System.currentTimeMillis() + settings.tailCompactionSearchMillis();
			PackagerResult baseline = best;
			try {
				best = new TailContainerCompactor(supportPolicy,
						settings.tailCompactionBeamWidth(), settings.tailCompactionBranching())
						.compact(best, compactionDeadline);
			} catch (RuntimeException e) {
				System.err.println("packing-service tail-compaction failed unexpectedly; keeping baseline: "
						+ e.getMessage());
				e.printStackTrace(System.err);
				best = baseline;
			}
		}
		return best;
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

	private PackagerResult packFlat(PackingPlan plan, long deadline) {
		return packFlat(plan, deadline, PackingLogContext.NONE);
	}

	private PackagerResult packFlat(PackingPlan plan, long deadline, PackingLogContext context) {
		PackagerResult best = null;
		best = better(best, packOrientation(plan, false, deadline, context));
		if (best != null && best.isSuccess() && best.size() == 1) {
			return best;
		}
		best = better(best, packOrientation(plan, true, deadline, context));
		return best;
	}

	private PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth, long deadline,
			PackingLogContext context) {
		List<ContainerItem> containers = swapLengthWidth ? swappedContainers(plan.containerItems()) : plan.containerItems();
		PackagerResult best = null;
		PackagerResult blockBeam = blockBeamStrategy.pack(plan, containers,
				"BlockBeam" + (swapLengthWidth ? "-SWAPPED" : ""), deadline, context);
		if (blockBeam != null && blockBeam.isSuccess()) {
			return blockBeam;
		}
		if (settings.blockBeamOnly()) {
			return null;
		}
		return legacyStrategy.pack(plan, containers, swapLengthWidth, deadline, context);
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

	private static String terminationReason(int candidates, int attempted, int successful, long deadline) {
		if (System.currentTimeMillis() >= deadline) return "deadline";
		if (successful > 0 && attempted < candidates) return "first-success";
		if (attempted >= candidates) return "candidates-exhausted";
		return "stopped";
	}

}
