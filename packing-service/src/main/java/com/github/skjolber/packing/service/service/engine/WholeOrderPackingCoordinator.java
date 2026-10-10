package com.github.skjolber.packing.service.service.engine;

import static com.github.skjolber.packing.service.service.logging.PackingDiagnostics.elapsedMillis;
import static com.github.skjolber.packing.service.service.logging.PackingDiagnostics.houseBillCount;
import static com.github.skjolber.packing.service.service.logging.PackingDiagnostics.unitCount;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.service.PackingPlan;
import com.github.skjolber.packing.service.service.assignment.WholeOrderAssignmentSolver;
import com.github.skjolber.packing.service.service.assignment.WholeOrderCandidateSearch;
import com.github.skjolber.packing.service.service.assignment.WholeOrderPackingCache;
import com.github.skjolber.packing.service.service.compaction.AutomaticContainerFallback;
import com.github.skjolber.packing.service.service.compaction.TailContainerCompactor;
import com.github.skjolber.packing.service.service.logging.PackingLogContext;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

/** Coordinates assignment candidate validation, caching and optional tail compaction. */
final class WholeOrderPackingCoordinator {

	private static final long SEARCH_MILLIS = 180_000L;
	private static final AtomicLong SEARCH_SEQUENCE = new AtomicLong();

	@FunctionalInterface
	interface ContainerPacker {
		PackagerResult pack(PackingPlan plan, long deadline, PackingLogContext context);
	}

	private final WholeOrderAssignmentSolver assignmentSolver = new WholeOrderAssignmentSolver();
	private final WholeOrderCandidateSearch candidateSearch;
	private final WholeOrderCandidateValidator candidateValidator;
	private final PlacementSupport.Policy supportPolicy;
	private final PackingEngineSettings settings;

	WholeOrderPackingCoordinator(PlacementSupport.Policy supportPolicy, PackingEngineSettings settings) {
		this.supportPolicy = supportPolicy;
		this.settings = settings;
		this.candidateSearch = new WholeOrderCandidateSearch(supportPolicy,
				settings.candidateProbeEnabled(), settings.candidateProbeWindow(), settings.candidateProbeStates());
		this.candidateValidator = new WholeOrderCandidateValidator(settings.diagnosticsEnabled());
	}

	PackagerResult pack(PackingPlan plan, boolean compactTail, int compactionTargetCount,
			ContainerPacker packer) {
		long searchStarted = System.nanoTime();
		long deadline = System.currentTimeMillis() + SEARCH_MILLIS;
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
				+ " deadlineMs=" + SEARCH_MILLIS
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
								cache, packer::pack);
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
								cache, packer::pack)));
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

	private static String terminationReason(int candidates, int attempted, int successful, long deadline) {
		if (System.currentTimeMillis() >= deadline) return "deadline";
		if (successful > 0 && attempted < candidates) return "first-success";
		if (attempted >= candidates) return "candidates-exhausted";
		return "stopped";
	}
}
