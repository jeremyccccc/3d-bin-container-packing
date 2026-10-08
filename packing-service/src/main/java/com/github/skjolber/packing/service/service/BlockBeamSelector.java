package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.skjolber.packing.api.Container;

final class BlockBeamSelector {

	private final int beamWidth;
	private final BlockBeamSearchPackager.SearchProfile searchProfile;

	BlockBeamSelector(int beamWidth, BlockBeamSearchPackager.SearchProfile searchProfile) {
		this.beamWidth = beamWidth;
		this.searchProfile = searchProfile;
	}

	List<BlockBeamSearchPackager.State> select(List<BlockBeamSearchPackager.State> candidates,
			Container container) {
		if (candidates.size() <= beamWidth) {
			candidates.sort(BlockBeamStateEvaluator.comparator(container, searchProfile));
			return candidates;
		}

		Comparator<BlockBeamSearchPackager.State> overall =
				BlockBeamStateEvaluator.comparator(container, searchProfile);
		Comparator<BlockBeamSearchPackager.State> criticalSpace = Comparator
				.comparingInt((BlockBeamSearchPackager.State state) -> state.feasibility().strandedTypes())
				.thenComparing(Comparator.comparingInt(
						(BlockBeamSearchPackager.State state) -> state.feasibility().minimumFitSpaces()).reversed())
				.thenComparingInt(state -> state.feasibility().scarceTypes())
				.thenComparing(Comparator.comparingInt(
						(BlockBeamSearchPackager.State state) -> state.feasibility().minimumClearance()).reversed())
				.thenComparing(overall);
		Comparator<BlockBeamSearchPackager.State> openSpace = Comparator
				.comparingLong((BlockBeamSearchPackager.State state) ->
						BlockBeamStateEvaluator.largestSpace(state.spaces())).reversed()
				.thenComparingInt(state -> state.spaces().size())
				.thenComparing(overall);

		LinkedHashSet<BlockBeamSearchPackager.State> selected = new LinkedHashSet<>(beamWidth * 2);
		addBest(selected, candidates, overall,
				availableQuota(selected, Math.max(1, beamWidth / 2)));
		addBest(selected, candidates, criticalSpace,
				availableQuota(selected, Math.max(1, beamWidth / 4)));
		addBest(selected, candidates, openSpace,
				availableQuota(selected, Math.max(1, beamWidth / 8)));
		addShapeDiversity(selected, candidates, overall, container,
				availableQuota(selected, Math.max(1, beamWidth / 8)));
		addBest(selected, candidates, overall, beamWidth - selected.size());
		return new ArrayList<>(selected);
	}

	private int availableQuota(Set<BlockBeamSearchPackager.State> selected, int requested) {
		return Math.max(0, Math.min(requested, beamWidth - selected.size()));
	}

	private static void addBest(Set<BlockBeamSearchPackager.State> selected,
			List<BlockBeamSearchPackager.State> candidates,
			Comparator<BlockBeamSearchPackager.State> comparator, int count) {
		if (count <= 0) return;
		List<BlockBeamSearchPackager.State> sorted = new ArrayList<>(candidates);
		sorted.sort(comparator);
		int added = 0;
		for (BlockBeamSearchPackager.State state : sorted) {
			if (selected.add(state) && ++added >= count) return;
		}
	}

	private static void addShapeDiversity(Set<BlockBeamSearchPackager.State> selected,
			List<BlockBeamSearchPackager.State> candidates,
			Comparator<BlockBeamSearchPackager.State> comparator,
			Container container, int count) {
		if (count <= 0) return;
		List<BlockBeamSearchPackager.State> sorted = new ArrayList<>(candidates);
		sorted.sort(comparator);
		Set<String> buckets = new HashSet<>();
		int added = 0;
		for (BlockBeamSearchPackager.State state : sorted) {
			int lengthBucket = 4 * state.maxX() / Math.max(1, container.getLoadDx());
			int heightBucket = 4 * state.maxZ() / Math.max(1, container.getLoadDz());
			int spaceBucket = Math.min(7, state.spaces().size() / 4);
			String bucket = lengthBucket + ":" + heightBucket + ":" + spaceBucket;
			if (selected.contains(state) || !buckets.add(bucket)) continue;
			selected.add(state);
			if (++added >= count) return;
		}
	}
}
