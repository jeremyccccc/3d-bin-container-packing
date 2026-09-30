package com.github.skjolber.packing.service.service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;

final class BlockBeamStateEvaluator {

	private static final int TRACKED_FIT_SPACES = 4;

	private BlockBeamStateEvaluator() {
	}

	static BlockBeamSearchPackager.Feasibility feasibility(int[] remaining,
			List<BlockBeamSearchPackager.Space> spaces, List<BoxItem> items) {
		int strandedTypes = 0;
		int scarceTypes = 0;
		int minimumFitSpaces = Integer.MAX_VALUE;
		int minimumClearance = Integer.MAX_VALUE;
		for (int itemIndex = 0; itemIndex < remaining.length; itemIndex++) {
			if (remaining[itemIndex] == 0) continue;
			Box box = items.get(itemIndex).getBox();
			int fitSpaces = 0;
			int bestClearance = -1;
			for (BlockBeamSearchPackager.Space space : spaces) {
				int clearance = clearance(box, space);
				if (clearance < 0) continue;
				fitSpaces++;
				bestClearance = Math.max(bestClearance, clearance);
				if (fitSpaces >= TRACKED_FIT_SPACES) break;
			}
			if (fitSpaces == 0) {
				strandedTypes++;
				minimumFitSpaces = 0;
				minimumClearance = -1;
				continue;
			}
			minimumFitSpaces = Math.min(minimumFitSpaces, fitSpaces);
			minimumClearance = Math.min(minimumClearance, bestClearance);
			if (fitSpaces <= 2) scarceTypes++;
		}
		if (minimumFitSpaces == Integer.MAX_VALUE) minimumFitSpaces = TRACKED_FIT_SPACES;
		if (minimumClearance == Integer.MAX_VALUE) minimumClearance = 0;
		return new BlockBeamSearchPackager.Feasibility(
				strandedTypes, scarceTypes, minimumFitSpaces, minimumClearance);
	}

	static Comparator<BlockBeamSearchPackager.State> comparator(Container container,
			BlockBeamSearchPackager.SearchProfile profile) {
		Comparator<BlockBeamSearchPackager.State> feasibilityStage = Comparator
				.comparingInt((BlockBeamSearchPackager.State state) -> state.feasibility().strandedTypes())
				.thenComparingInt(state -> state.feasibility().scarceTypes())
				.thenComparing(Comparator.comparingInt(
						(BlockBeamSearchPackager.State state) -> state.feasibility().minimumFitSpaces()).reversed())
				.thenComparing(Comparator.comparingInt(
						(BlockBeamSearchPackager.State state) -> state.feasibility().minimumClearance()).reversed());
		Comparator<BlockBeamSearchPackager.State> qualityStage = switch (profile) {
			case BALANCED -> Comparator.comparingDouble(
					(BlockBeamSearchPackager.State state) -> state.score(container)).reversed();
			case VOLUME_FIRST -> Comparator.comparingLong(BlockBeamSearchPackager.State::packedVolume).reversed()
					.thenComparing(Comparator.comparingLong(
							(BlockBeamSearchPackager.State state) -> largestSpace(state.spaces())).reversed());
			case SPACE_EFFICIENCY -> Comparator
					.comparingLong((BlockBeamSearchPackager.State state) -> largestSpace(state.spaces())).reversed()
					.thenComparingInt(state -> state.spaces().size())
					.thenComparing(Comparator.comparingLong(
							BlockBeamSearchPackager.State::packedVolume).reversed());
			case COMPACT_FRONT -> Comparator.comparingInt(BlockBeamSearchPackager.State::maxX)
					.thenComparingInt(BlockBeamSearchPackager.State::maxZ)
					.thenComparing(Comparator.comparingLong(
							BlockBeamSearchPackager.State::packedVolume).reversed());
		};
		return qualityStage.thenComparing(feasibilityStage);
	}

	static Comparator<BlockBeamSearchPackager.State> partialComparator(long fixedVolume) {
		return Comparator.comparingLong(
				(BlockBeamSearchPackager.State state) -> state.packedVolume() - fixedVolume)
				.thenComparingInt(state -> -Arrays.stream(state.remaining()).sum())
				.thenComparingLong(state -> largestSpace(state.spaces()));
	}

	static boolean betterPartial(BlockBeamSearchPackager.State candidate,
			BlockBeamSearchPackager.State current, long fixedVolume) {
		return partialComparator(fixedVolume).compare(candidate, current) > 0;
	}

	static String eliteShape(BlockBeamSearchPackager.State state, Container container) {
		int lengthBucket = 8 * state.maxX() / Math.max(1, container.getLoadDx());
		int heightBucket = 8 * state.maxZ() / Math.max(1, container.getLoadDz());
		int spaceBucket = Math.min(15, state.spaces().size() / 3);
		int scarceBucket = Math.min(7, state.feasibility().scarceTypes());
		return lengthBucket + ":" + heightBucket + ":" + spaceBucket + ":" + scarceBucket;
	}

	static long largestSpace(List<BlockBeamSearchPackager.Space> spaces) {
		long largest = 0L;
		for (BlockBeamSearchPackager.Space space : spaces) {
			largest = Math.max(largest, space.volume());
		}
		return largest;
	}

	private static int clearance(Box box, BlockBeamSearchPackager.Space space) {
		int best = -1;
		for (BoxStackValue orientation : box.getStackValues()) {
			if (orientation.getDx() > space.dx() || orientation.getDy() > space.dy()
					|| orientation.getDz() > space.dz()) continue;
			best = Math.max(best, Math.min(space.dx() - orientation.getDx(),
					Math.min(space.dy() - orientation.getDy(), space.dz() - orientation.getDz())));
		}
		return best;
	}
}
