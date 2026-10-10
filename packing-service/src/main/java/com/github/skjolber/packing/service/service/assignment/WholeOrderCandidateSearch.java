package com.github.skjolber.packing.service.service.assignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

/**
 * Controls the order in which whole-order assignment candidates are validated.
 * The actual 3D validation remains supplied by the caller so candidate ordering
 * and container packing can evolve independently.
 */
public final class WholeOrderCandidateSearch {

	@FunctionalInterface
	public interface Validator {
		PackagerResult validate(WholeOrderAssignmentSolver.Candidate candidate, int attempt);
	}

	public record Result(PackagerResult result, int attempted) {
	}

	private final WholeOrderCandidateSelector selector;
	private final WholeOrderCandidateProbeRanker probeRanker;
	private final boolean probeEnabled;
	private final int probeWindow;

	public WholeOrderCandidateSearch(PlacementSupport.Policy supportPolicy,
			boolean probeEnabled, int probeWindow, int probeStates) {
		this.selector = new WholeOrderCandidateSelector();
		this.probeRanker = new WholeOrderCandidateProbeRanker(supportPolicy, probeStates);
		this.probeEnabled = probeEnabled;
		this.probeWindow = probeWindow;
	}

	public Result search(List<WholeOrderAssignmentSolver.Candidate> candidates,
			List<ContainerItem> physicalContainers, WholeOrderPackingCache cache,
			long searchId, long deadline, Validator validator) {
		if (probeEnabled) {
			return searchWithProbe(candidates, physicalContainers, cache, searchId, deadline, validator);
		}
		return searchStaticOrder(candidates, physicalContainers, cache, searchId, deadline, validator);
	}

	private Result searchStaticOrder(List<WholeOrderAssignmentSolver.Candidate> candidates,
			List<ContainerItem> physicalContainers, WholeOrderPackingCache cache,
			long searchId, long deadline, Validator validator) {
		List<WholeOrderCandidateSelector.IndexedCandidate> remaining = indexed(candidates);
		int attempted = 0;
		while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
			WholeOrderCandidateSelector.Selection selection = selector.select(
					remaining, physicalContainers, cache);
			if (selection.indexed() == null) {
				System.out.println("packing-service whole-order adaptive-stop searchId=" + searchId
						+ " reason=all-remaining-contain-proven-failure"
						+ " remaining=" + remaining.size());
				break;
			}
			WholeOrderCandidateSelector.IndexedCandidate indexed = selection.indexed();
			remaining.remove(indexed);
			attempted++;
			System.out.println("packing-service whole-order adaptive-select searchId=" + searchId
					+ " attempt=" + attempted + " originalRank=" + (indexed.originalIndex() + 1)
					+ " source=" + safe(indexed.candidate().source())
					+ " cachedSuccesses=" + selection.evidence().successes()
					+ " cachedSavedMs=" + selection.evidence().savedMillis()
					+ " prunedByFailure=" + selection.pruned());
			PackagerResult result = validator.validate(indexed.candidate(), attempted);
			if (result != null) return new Result(result, attempted);
		}
		return new Result(null, attempted);
	}

	private Result searchWithProbe(List<WholeOrderAssignmentSolver.Candidate> candidates,
			List<ContainerItem> physicalContainers, WholeOrderPackingCache cache,
			long searchId, long deadline, Validator validator) {
		List<WholeOrderCandidateSelector.IndexedCandidate> remaining = indexed(candidates);
		Deque<WholeOrderCandidateProbeRanker.ProbedCandidate> window = new ArrayDeque<>();
		int attempted = 0;
		int pruned = 0;
		while ((!remaining.isEmpty() || !window.isEmpty()) && System.currentTimeMillis() < deadline) {
			while (!window.isEmpty() && selector.containsProvenFailure(
					window.peekFirst().indexed().candidate(), physicalContainers, cache)) {
				window.removeFirst();
				pruned++;
			}
			if (window.isEmpty()) {
				List<WholeOrderCandidateSelector.IndexedCandidate> probeCandidates = new ArrayList<>();
				while (!remaining.isEmpty() && probeCandidates.size() < probeWindow) {
					WholeOrderCandidateSelector.IndexedCandidate indexed = remaining.remove(0);
					if (selector.containsProvenFailure(indexed.candidate(), physicalContainers, cache)) {
						pruned++;
						continue;
					}
					probeCandidates.add(indexed);
				}
				if (probeCandidates.isEmpty()) continue;
				List<WholeOrderCandidateProbeRanker.ProbedCandidate> ranked = probeRanker.rank(
						probeCandidates, physicalContainers, cache, searchId);
				for (WholeOrderCandidateProbeRanker.ProbedCandidate probed : ranked) {
					if (probed.score().provenFailure()) pruned++;
					else window.addLast(probed);
				}
				System.out.println("packing-service whole-order candidate-probe-window searchId=" + searchId
						+ " size=" + probeCandidates.size()
						+ " ranked=" + window.stream()
								.map(value -> Integer.toString(value.indexed().originalIndex() + 1)).toList()
						+ " pruned=" + pruned);
				continue;
			}
			WholeOrderCandidateProbeRanker.ProbedCandidate probed = window.removeFirst();
			WholeOrderCandidateSelector.IndexedCandidate indexed = probed.indexed();
			attempted++;
			System.out.println("packing-service whole-order adaptive-select searchId=" + searchId
					+ " attempt=" + attempted + " originalRank=" + (indexed.originalIndex() + 1)
					+ " source=" + safe(indexed.candidate().source())
					+ " probeMinVolume=" + rounded(probed.score().minimumVolumeCompletion())
					+ " probeAvgVolume=" + rounded(probed.score().averageVolumeCompletion())
					+ " prunedByFailure=" + pruned);
			PackagerResult result = validator.validate(indexed.candidate(), attempted);
			if (result != null) return new Result(result, attempted);
		}
		return new Result(null, attempted);
	}

	private static List<WholeOrderCandidateSelector.IndexedCandidate> indexed(
			List<WholeOrderAssignmentSolver.Candidate> candidates) {
		List<WholeOrderCandidateSelector.IndexedCandidate> result = new ArrayList<>(candidates.size());
		for (int index = 0; index < candidates.size(); index++) {
			result.add(new WholeOrderCandidateSelector.IndexedCandidate(candidates.get(index), index));
		}
		return result;
	}

	private static double rounded(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	private static String safe(String value) {
		return value == null ? "" : value.replace(' ', '_');
	}
}
