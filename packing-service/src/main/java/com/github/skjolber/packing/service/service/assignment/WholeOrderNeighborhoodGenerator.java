package com.github.skjolber.packing.service.service.assignment;

import java.util.List;
import java.util.Set;

import com.github.skjolber.packing.api.Container;

final class WholeOrderNeighborhoodGenerator {

	void addLocalMoves(List<WholeOrderAssignmentSolver.Candidate> result, Set<String> signatures,
			List<WholeOrderGroup> groups, List<Container> containers, int[] base, int maximumCandidates) {
		int limit = Math.min(maximumCandidates, result.size() + 6);
		long[] volumes = WholeOrderAssignmentSupport.loads(groups, base, containers.size(), true);
		long[] weights = WholeOrderAssignmentSupport.loads(groups, base, containers.size(), false);
		for (int groupIndex = 0; groupIndex < groups.size() && result.size() < limit; groupIndex++) {
			int from = base[groupIndex];
			WholeOrderGroup group = groups.get(groupIndex);
			for (int to = 0; to < containers.size() && result.size() < limit; to++) {
				if (to == from || !WholeOrderAssignmentSupport.fits(
						group, containers.get(to), volumes[to], weights[to])) continue;
				int[] moved = base.clone();
				moved[groupIndex] = to;
				WholeOrderAssignmentSupport.addCandidate(
						result, signatures, groups, containers, moved, "local-move");
			}
		}

		for (int left = 0; left < groups.size() && result.size() < limit; left++) {
			for (int right = left + 1; right < groups.size() && result.size() < limit; right++) {
				int leftContainer = base[left];
				int rightContainer = base[right];
				if (leftContainer == rightContainer) continue;
				WholeOrderGroup leftGroup = groups.get(left);
				WholeOrderGroup rightGroup = groups.get(right);
				long newLeftVolume = volumes[leftContainer] - leftGroup.volume() + rightGroup.volume();
				long newRightVolume = volumes[rightContainer] - rightGroup.volume() + leftGroup.volume();
				long newLeftWeight = weights[leftContainer] - leftGroup.weight() + rightGroup.weight();
				long newRightWeight = weights[rightContainer] - rightGroup.weight() + leftGroup.weight();
				if (!WholeOrderAssignmentSupport.withinCapacity(
						rightGroup, containers.get(leftContainer), newLeftVolume, newLeftWeight)
						|| !WholeOrderAssignmentSupport.withinCapacity(
						leftGroup, containers.get(rightContainer), newRightVolume, newRightWeight)) continue;
				int[] swapped = base.clone();
				swapped[left] = rightContainer;
				swapped[right] = leftContainer;
				WholeOrderAssignmentSupport.addCandidate(
						result, signatures, groups, containers, swapped, "local-swap");
			}
		}
	}
}
