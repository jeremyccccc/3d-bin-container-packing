package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;

/**
 * Re-ranks untested whole-order assignments from exact request-local packing
 * evidence. Static geometry determines the initial order. Once a container
 * load has been proved infeasible, candidates containing that exact subproblem
 * are pruned. A feasible cabinet is useful for avoiding repeated work, but it
 * must not change assignment order: one feasible cabinet does not prove that
 * the complete multi-cabinet assignment is promising.
 */
final class WholeOrderCandidateSelector {

	Selection select(List<IndexedCandidate> remaining, List<ContainerItem> containers,
			WholeOrderPackingCache cache) {
		List<Ranked> ranked = new ArrayList<>(remaining.size());
		int pruned = 0;
		for (IndexedCandidate indexed : remaining) {
			Evidence evidence = evidence(indexed.candidate(), containers, cache);
			if (evidence.failures() > 0) {
				pruned++;
				continue;
			}
			ranked.add(new Ranked(indexed, evidence));
		}
		Ranked best = ranked.stream().min(Comparator
				.comparingInt(value -> value.indexed().originalIndex()))
				.orElse(null);
		return new Selection(best == null ? null : best.indexed(), pruned,
				best == null ? Evidence.NONE : best.evidence());
	}

	private static Evidence evidence(WholeOrderAssignmentSolver.Candidate candidate,
			List<ContainerItem> containers, WholeOrderPackingCache cache) {
		int successes = 0;
		int failures = 0;
		long savedMillis = 0L;
		for (int index = 0; index < candidate.itemsByContainer().size(); index++) {
			List<BoxItem> items = candidate.itemsByContainer().get(index);
			if (items.isEmpty()) continue;
			Container container = containers.get(index).getContainer();
			WholeOrderPackingCache.Evidence evidence = cache.evidence(container, items);
			if (evidence.status() == WholeOrderPackingCache.Status.SUCCESS) successes++;
			else if (evidence.status() == WholeOrderPackingCache.Status.FAILURE) failures++;
			savedMillis += evidence.computationMillis();
		}
		return new Evidence(successes, failures, savedMillis);
	}

	boolean containsProvenFailure(WholeOrderAssignmentSolver.Candidate candidate,
			List<ContainerItem> containers, WholeOrderPackingCache cache) {
		return evidence(candidate, containers, cache).failures() > 0;
	}

	record IndexedCandidate(WholeOrderAssignmentSolver.Candidate candidate, int originalIndex) {
	}

	record Selection(IndexedCandidate indexed, int pruned, Evidence evidence) {
	}

	record Evidence(int successes, int failures, long savedMillis) {
		private static final Evidence NONE = new Evidence(0, 0, 0L);
	}

	private record Ranked(IndexedCandidate indexed, Evidence evidence) {
	}
}
