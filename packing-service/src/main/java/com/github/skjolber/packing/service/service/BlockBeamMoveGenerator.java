package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Placement;

final class BlockBeamMoveGenerator {

	private final int branching;
	private final PlacementSupport.Policy supportPolicy;
	private final BlockBeamSearchPackager.SearchProfile searchProfile;

	BlockBeamMoveGenerator(int branching, PlacementSupport.Policy supportPolicy,
			BlockBeamSearchPackager.SearchProfile searchProfile) {
		this.branching = branching;
		this.supportPolicy = supportPolicy;
		this.searchProfile = searchProfile;
	}

	List<BlockBeamSearchPackager.Move> moves(BlockBeamSearchPackager.State state,
			List<BlockBeamSearchPackager.Block> catalog, Container container,
			List<Placement> fixedPlacements, BlockBeamFixedPlacementIndex fixedPlacementIndex) {
		PriorityQueue<BlockBeamSearchPackager.Move> best = new PriorityQueue<>(
				Comparator.comparingDouble(BlockBeamSearchPackager.Move::rank));
		Map<Integer, BlockBeamSearchPackager.Move> bestByItem = new HashMap<>();
		for (int spaceIndex = 0; spaceIndex < state.spaces().size(); spaceIndex++) {
			BlockBeamSearchPackager.Space space = state.spaces().get(spaceIndex);
			for (BlockBeamSearchPackager.Block block : catalog) {
				if (state.remaining()[block.itemIndex()] < block.units() || !space.fits(block)
						|| state.intersects(space.x(), space.y(), space.z(), block)
						|| fixedPlacementIndex.intersects(space.x(), space.y(), space.z(), block)) continue;
				// Full-container layouts are validated when complete. Fixed-layout insertion
				// must enforce support while exploring because existing cargo is immutable.
				if (!fixedPlacements.isEmpty() && !BlockBeamSupportValidator.isSupported(
						space.x(), space.y(), space.z(), block,
						fixedPlacements, state.placements(), supportPolicy)) continue;
				if ((long) state.weight() + block.weight() > container.getMaxLoadWeight()) continue;
				double rank = rank(space, block, container, searchProfile);
				BlockBeamSearchPackager.Move move = new BlockBeamSearchPackager.Move(
						spaceIndex, block, false, rank);
				add(best, move);
				bestByItem.merge(block.itemIndex(), move,
						(first, second) -> first.rank() >= second.rank() ? first : second);
			}
		}
		Map<String, BlockBeamSearchPackager.Move> unique = new LinkedHashMap<>();
		for (BlockBeamSearchPackager.Move move : best) unique.put(move.key(), move);
		for (BlockBeamSearchPackager.Move move : bestByItem.values()) unique.put(move.key(), move);
		List<BlockBeamSearchPackager.Move> result = new ArrayList<>(unique.values());
		result.sort(Comparator.comparingDouble(BlockBeamSearchPackager.Move::rank).reversed());
		return result;
	}

	private void add(PriorityQueue<BlockBeamSearchPackager.Move> best,
			BlockBeamSearchPackager.Move move) {
		if (best.size() < branching) {
			best.add(move);
		} else if (move.rank() > best.peek().rank()) {
			best.poll();
			best.add(move);
		}
	}

	private static double rank(BlockBeamSearchPackager.Space space,
			BlockBeamSearchPackager.Block block, Container container,
			BlockBeamSearchPackager.SearchProfile profile) {
		double fill = block.volume() / (double) space.volume();
		double footprint = (long) block.dx() * block.dy() / (double) ((long) space.dx() * space.dy());
		double wall = (space.x() == 0 ? 1 : 0) + (space.y() == 0 ? 1 : 0)
				+ (space.x() + block.dx() == container.getLoadDx() ? 1 : 0)
				+ (space.y() + block.dy() == container.getLoadDy() ? 1 : 0);
		double balanced = block.volume() / (double) container.getMaxLoadVolume() * 10_000_000.0
				+ block.units() * 100.0
				+ fill * 100_000.0 + footprint * 20_000.0 + wall * 1_000.0
				- space.z() * 0.01;
		return switch (profile) {
			case BALANCED -> balanced;
			case VOLUME_FIRST -> balanced
					+ block.volume() / (double) container.getMaxLoadVolume() * 8_000_000.0;
			case SPACE_EFFICIENCY -> balanced + fill * 180_000.0 + footprint * 80_000.0;
			case COMPACT_FRONT -> balanced + wall * 8_000.0
					- space.x() / (double) container.getLoadDx() * 20_000.0
					- space.z() / (double) container.getLoadDz() * 10_000.0;
		};
	}
}
