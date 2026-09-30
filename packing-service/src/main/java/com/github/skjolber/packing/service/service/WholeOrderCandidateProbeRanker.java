package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;

/**
 * Deterministic racing inside a bounded window of whole-order candidates.
 * Every unknown one-container subproblem receives exactly the same number of
 * Block Beam state expansions. Partial progress only changes order inside the
 * window; it never proves failure and never removes a candidate.
 */
final class WholeOrderCandidateProbeRanker {

	private final PlacementSupport.Policy supportPolicy;
	private final int expandedStatesPerContainer;

	WholeOrderCandidateProbeRanker(PlacementSupport.Policy supportPolicy,
			int expandedStatesPerContainer) {
		if (expandedStatesPerContainer <= 0) {
			throw new IllegalArgumentException("expandedStatesPerContainer must be positive");
		}
		this.supportPolicy = supportPolicy;
		this.expandedStatesPerContainer = expandedStatesPerContainer;
	}

	List<ProbedCandidate> rank(List<WholeOrderCandidateSelector.IndexedCandidate> window,
			List<ContainerItem> containers, WholeOrderPackingCache cache, long searchId) {
		List<ProbedCandidate> probed = new ArrayList<>(window.size());
		for (WholeOrderCandidateSelector.IndexedCandidate indexed : window) {
			probed.add(probe(indexed, containers, cache, searchId));
		}
		probed.sort(Comparator
				.comparing((ProbedCandidate value) -> value.score().provenFailure())
				.thenComparingInt(value -> -value.score().completeContainers())
				.thenComparingDouble(value -> -value.score().minimumVolumeCompletion())
				.thenComparingDouble(value -> -value.score().averageVolumeCompletion())
				.thenComparingDouble(value -> -value.score().minimumUnitCompletion())
				.thenComparingDouble(value -> -value.score().averageUnitCompletion())
				.thenComparingInt(value -> value.indexed().originalIndex()));
		// Keep the established static leader as the incumbent. A very short
		// rollout is useful for choosing which challenger to try after the
		// incumbent fails, but is not reliable enough to overrule it.
		WholeOrderCandidateSelector.IndexedCandidate incumbent = window.stream()
				.min(Comparator.comparingInt(WholeOrderCandidateSelector.IndexedCandidate::originalIndex))
				.orElse(null);
		if (incumbent != null) {
			int incumbentIndex = -1;
			for (int index = 0; index < probed.size(); index++) {
				if (probed.get(index).indexed() == incumbent) {
					incumbentIndex = index;
					break;
				}
			}
			if (incumbentIndex > 0 && !probed.get(incumbentIndex).score().provenFailure()) {
				ProbedCandidate value = probed.remove(incumbentIndex);
				probed.add(0, value);
			}
		}
		return List.copyOf(probed);
	}

	private ProbedCandidate probe(WholeOrderCandidateSelector.IndexedCandidate indexed,
			List<ContainerItem> containers, WholeOrderPackingCache cache, long searchId) {
		int completeContainers = 0;
		boolean provenFailure = false;
		double minimumVolume = 1.0;
		double totalVolume = 0.0;
		double minimumUnits = 1.0;
		double totalUnits = 0.0;
		int nonEmptyContainers = 0;
		long started = System.nanoTime();
		for (int index = 0; index < indexed.candidate().itemsByContainer().size(); index++) {
			List<BoxItem> items = indexed.candidate().itemsByContainer().get(index);
			if (items.isEmpty()) continue;
			nonEmptyContainers++;
			Container container = containers.get(index).getContainer();
			WholeOrderPackingCache.Evidence evidence = cache.evidence(container, items);
			double volumeCompletion;
			double unitCompletion;
			if (evidence.status() == WholeOrderPackingCache.Status.SUCCESS) {
				completeContainers++;
				volumeCompletion = 1.0;
				unitCompletion = 1.0;
			} else if (evidence.status() == WholeOrderPackingCache.Status.FAILURE) {
				provenFailure = true;
				volumeCompletion = 0.0;
				unitCompletion = 0.0;
			} else {
				long probeStarted = System.nanoTime();
				BlockBeamSearchPackager.SearchProgress progress = new BlockBeamSearchPackager(supportPolicy)
						.probe(container, items, expandedStatesPerContainer,
								" searchId=" + searchId + " phase=candidate-probe"
										+ " originalRank=" + (indexed.originalIndex() + 1)
										+ " container=" + (index + 1) + "/" + containers.size());
				long elapsedMillis = elapsedMillis(probeStarted);
				volumeCompletion = progress.volumeCompletionRatio();
				unitCompletion = progress.completionRatio();
				if (progress.complete()) {
					completeContainers++;
					cache.putSuccess(container, items, progress.result(), elapsedMillis);
				} else if (progress.exhausted()) {
					provenFailure = true;
					cache.putFailure(container, items, elapsedMillis, true);
				}
			}
			minimumVolume = Math.min(minimumVolume, volumeCompletion);
			totalVolume += volumeCompletion;
			minimumUnits = Math.min(minimumUnits, unitCompletion);
			totalUnits += unitCompletion;
		}
		if (nonEmptyContainers == 0) {
			minimumVolume = 0.0;
			minimumUnits = 0.0;
		}
		ProbeScore score = new ProbeScore(provenFailure, completeContainers,
				minimumVolume, totalVolume / Math.max(1, nonEmptyContainers),
				minimumUnits, totalUnits / Math.max(1, nonEmptyContainers));
		System.out.println("packing-service whole-order candidate-probe searchId=" + searchId
				+ " originalRank=" + (indexed.originalIndex() + 1)
				+ " source=" + indexed.candidate().source()
				+ " statesPerContainer=" + expandedStatesPerContainer
				+ " completeContainers=" + completeContainers
				+ " provenFailure=" + provenFailure
				+ " minVolume=" + rounded(score.minimumVolumeCompletion())
				+ " avgVolume=" + rounded(score.averageVolumeCompletion())
				+ " minUnits=" + rounded(score.minimumUnitCompletion())
				+ " avgUnits=" + rounded(score.averageUnitCompletion())
				+ " elapsedMs=" + elapsedMillis(started));
		return new ProbedCandidate(indexed, score);
	}

	private static long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private static double rounded(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	record ProbedCandidate(WholeOrderCandidateSelector.IndexedCandidate indexed, ProbeScore score) {
	}

	record ProbeScore(boolean provenFailure, int completeContainers,
			double minimumVolumeCompletion, double averageVolumeCompletion,
			double minimumUnitCompletion, double averageUnitCompletion) {
	}
}
