package com.github.skjolber.packing.service.service.assignment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.service.service.PackingPlan;

/** Assigns each complete house bill to exactly one physical container. */
public final class WholeOrderAssignmentSolver {

	private static final int GREEDY_SEEDS = 24;
	private static final int MAX_CANDIDATES = 256;
	private static final long SYSTEMATIC_NODE_LIMIT = 2_000_000L;

	private final WholeOrderCandidateScorer candidateScorer = new WholeOrderCandidateScorer();
	private final WholeOrderGreedySeeder greedySeeder = new WholeOrderGreedySeeder();
	private final WholeOrderNeighborhoodGenerator neighborhoodGenerator = new WholeOrderNeighborhoodGenerator();
	private final WholeOrderSystematicSearch systematicSearch =
			new WholeOrderSystematicSearch(MAX_CANDIDATES, SYSTEMATIC_NODE_LIMIT);

	public List<Candidate> candidates(PackingPlan plan) {
		return candidates(plan, WholeOrderAssignmentStrategy.BALANCED, 0.85);
	}

	public List<Candidate> candidates(PackingPlan plan, WholeOrderAssignmentStrategy strategy,
			double targetFillRatio) {
		List<Container> containers = WholeOrderAssignmentSupport.expandContainers(plan.containerItems());
		List<WholeOrderGroup> groups = WholeOrderAssignmentSupport.group(plan.boxItems());
		if (containers.isEmpty() || groups.isEmpty()) return List.of();
		if (containers.size() == 1) return singleContainer(groups, containers, strategy, targetFillRatio);

		List<Candidate> candidates = new ArrayList<>();
		Set<String> signatures = new HashSet<>();
		List<int[]> greedyAssignments = new ArrayList<>();
		for (int seed = 0; seed < GREEDY_SEEDS && candidates.size() < MAX_CANDIDATES; seed++) {
			int[] assignment = greedySeeder.seed(groups, containers, seed, strategy, targetFillRatio);
			if (assignment != null) {
				WholeOrderAssignmentSupport.addCandidate(
						candidates, signatures, groups, containers, assignment, "greedy-" + seed);
				greedyAssignments.add(assignment);
			}
		}
		for (int[] assignment : greedyAssignments) {
			neighborhoodGenerator.addLocalMoves(
					candidates, signatures, groups, containers, assignment, MAX_CANDIDATES);
		}
		if (candidates.size() < MAX_CANDIDATES) {
			systematicSearch.addCandidates(candidates, signatures, groups, containers);
		}
		return candidateScorer.rank(candidates, containers, strategy, targetFillRatio);
	}

	private List<Candidate> singleContainer(List<WholeOrderGroup> groups, List<Container> containers,
			WholeOrderAssignmentStrategy strategy, double targetFillRatio) {
		List<Candidate> candidates = new ArrayList<>(1);
		WholeOrderAssignmentSupport.addCandidate(candidates, new HashSet<>(), groups, containers,
				new int[groups.size()], "single-container");
		return candidateScorer.rank(candidates, containers, strategy, targetFillRatio);
	}

	public record Candidate(List<List<BoxItem>> itemsByContainer, String source,
			int generationIndex, String signature, WholeOrderCandidateScorer.Score score) {
		Candidate withScore(WholeOrderCandidateScorer.Score score) {
			return new Candidate(itemsByContainer, source, generationIndex, signature, score);
		}
	}
}
