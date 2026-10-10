package com.github.skjolber.packing.service.service.assignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.skjolber.packing.api.Container;

final class WholeOrderSystematicSearch {

	private final int maximumCandidates;
	private final long nodeLimit;

	WholeOrderSystematicSearch(int maximumCandidates, long nodeLimit) {
		this.maximumCandidates = maximumCandidates;
		this.nodeLimit = nodeLimit;
	}

	void addCandidates(List<WholeOrderAssignmentSolver.Candidate> result, Set<String> signatures,
			List<WholeOrderGroup> groups, List<Container> containers) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) order.add(i);
		order.sort(Comparator.<Integer>comparingLong(index -> -groups.get(index).volume())
				.thenComparingLong(index -> -groups.get(index).largestBox())
				.thenComparing(index -> groups.get(index).stableKey()));
		new SearchState(result, signatures, groups, containers, order).search(0);
	}

	private final class SearchState {
		private final List<WholeOrderAssignmentSolver.Candidate> result;
		private final Set<String> signatures;
		private final List<WholeOrderGroup> groups;
		private final List<Container> containers;
		private final List<Integer> order;
		private final int[] assignment;
		private final long[] volumes;
		private final long[] weights;
		private long nodes;

		private SearchState(List<WholeOrderAssignmentSolver.Candidate> result, Set<String> signatures,
				List<WholeOrderGroup> groups, List<Container> containers, List<Integer> order) {
			this.result = result;
			this.signatures = signatures;
			this.groups = groups;
			this.containers = containers;
			this.order = order;
			this.assignment = new int[groups.size()];
			Arrays.fill(this.assignment, -1);
			this.volumes = new long[containers.size()];
			this.weights = new long[containers.size()];
		}

		private void search(int depth) {
			if (result.size() >= maximumCandidates || ++nodes > nodeLimit) return;
			if (depth == order.size()) {
				WholeOrderAssignmentSupport.addCandidate(
						result, signatures, groups, containers, assignment.clone(), "systematic");
				return;
			}
			int groupIndex = order.get(depth);
			WholeOrderGroup group = groups.get(groupIndex);
			List<Integer> destinations = new ArrayList<>();
			for (int i = 0; i < containers.size(); i++) destinations.add(i);
			destinations.sort(Comparator.comparingDouble((Integer i) -> containerFillAfter(i, group))
					.thenComparingInt(Integer::intValue));
			Set<String> equivalentLoads = new HashSet<>();
			for (int containerIndex : destinations) {
				Container container = containers.get(containerIndex);
				String loadSignature = container.getLoadDx() + "x" + container.getLoadDy() + "x" + container.getLoadDz()
						+ ":" + container.getMaxLoadVolume() + ":" + container.getMaxLoadWeight()
						+ ":" + volumes[containerIndex] + ":" + weights[containerIndex];
				if (!equivalentLoads.add(loadSignature) || !WholeOrderAssignmentSupport.fits(
						group, container, volumes[containerIndex], weights[containerIndex])) continue;
				assignment[groupIndex] = containerIndex;
				volumes[containerIndex] += group.volume();
				weights[containerIndex] += group.weight();
				search(depth + 1);
				volumes[containerIndex] -= group.volume();
				weights[containerIndex] -= group.weight();
				assignment[groupIndex] = -1;
				if (result.size() >= maximumCandidates || nodes > nodeLimit) return;
			}
		}

		private double containerFillAfter(int containerIndex, WholeOrderGroup group) {
			Container container = containers.get(containerIndex);
			return -(volumes[containerIndex] + group.volume()) / (double) container.getMaxLoadVolume();
		}
	}
}
