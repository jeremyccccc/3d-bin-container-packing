package com.github.skjolber.packing.service.service.assignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.github.skjolber.packing.api.Container;

final class WholeOrderGreedySeeder {

	int[] seed(List<WholeOrderGroup> groups, List<Container> containers, int seed,
			WholeOrderAssignmentStrategy strategy, double targetFillRatio) {
		return strategy == WholeOrderAssignmentStrategy.FILL_FIRST
				? fillFirst(groups, containers, seed, targetFillRatio)
				: balanced(groups, containers, seed);
	}

	private static int[] balanced(List<WholeOrderGroup> groups, List<Container> containers, int seed) {
		Random random = new Random(31L * seed + 17L);
		List<Integer> order = order(groups, random);
		long[] usedVolume = new long[containers.size()];
		long[] usedWeight = new long[containers.size()];
		int[] assignment = new int[groups.size()];
		Arrays.fill(assignment, -1);
		for (int groupIndex : order) {
			WholeOrderGroup group = groups.get(groupIndex);
			int best = -1;
			double bestScore = Double.POSITIVE_INFINITY;
			for (int containerIndex = 0; containerIndex < containers.size(); containerIndex++) {
				Container container = containers.get(containerIndex);
				if (!WholeOrderAssignmentSupport.fits(
						group, container, usedVolume[containerIndex], usedWeight[containerIndex])) continue;
				double volumeFill = (usedVolume[containerIndex] + group.volume())
						/ (double) container.getMaxLoadVolume();
				double weightFill = container.getMaxLoadWeight() == 0 ? 0.0
						: (usedWeight[containerIndex] + group.weight()) / (double) container.getMaxLoadWeight();
				double score = volumeFill + 0.15 * weightFill + random.nextDouble() * 0.01;
				if (score < bestScore) {
					bestScore = score;
					best = containerIndex;
				}
			}
			if (best < 0) return null;
			assignment[groupIndex] = best;
			usedVolume[best] += group.volume();
			usedWeight[best] += group.weight();
		}
		return assignment;
	}

	private static int[] fillFirst(List<WholeOrderGroup> groups, List<Container> containers,
			int seed, double configuredTargetFillRatio) {
		Random random = new Random(31L * seed + 17L);
		double relaxation = (seed % 6) * 0.03;
		double targetFillRatio = Math.max(0.60, Math.min(0.98,
				configuredTargetFillRatio - relaxation + (random.nextDouble() - 0.5) * 0.02));
		List<Integer> order = order(groups, random);
		long[] usedVolume = new long[containers.size()];
		long[] usedWeight = new long[containers.size()];
		int[] assignment = new int[groups.size()];
		Arrays.fill(assignment, -1);
		for (int groupIndex : order) {
			WholeOrderGroup group = groups.get(groupIndex);
			int best = -1;
			double bestScore = Double.POSITIVE_INFINITY;
			for (int containerIndex = 0; containerIndex < containers.size(); containerIndex++) {
				Container container = containers.get(containerIndex);
				if (!WholeOrderAssignmentSupport.fits(
						group, container, usedVolume[containerIndex], usedWeight[containerIndex])) continue;
				double volumeFill = (usedVolume[containerIndex] + group.volume())
						/ (double) container.getMaxLoadVolume();
				double weightFill = container.getMaxLoadWeight() == 0 ? 0.0
						: (usedWeight[containerIndex] + group.weight()) / (double) container.getMaxLoadWeight();
				boolean open = usedVolume[containerIndex] > 0L || usedWeight[containerIndex] > 0L;
				double score;
				if (volumeFill <= targetFillRatio && weightFill <= targetFillRatio) {
					score = (open ? 0.0 : 10.0) + (targetFillRatio - volumeFill)
							+ 0.15 * Math.max(0.0, targetFillRatio - weightFill)
							+ containerIndex * 0.0001;
				} else {
					score = 100.0 + Math.max(0.0, volumeFill - targetFillRatio) * 10.0
							+ Math.max(0.0, weightFill - targetFillRatio)
							+ (open ? 0.0 : 10.0) + containerIndex * 0.0001;
				}
				if (score < bestScore) {
					bestScore = score;
					best = containerIndex;
				}
			}
			if (best < 0) return null;
			assignment[groupIndex] = best;
			usedVolume[best] += group.volume();
			usedWeight[best] += group.weight();
		}
		return assignment;
	}

	private static List<Integer> order(List<WholeOrderGroup> groups, Random random) {
		List<Integer> order = new ArrayList<>();
		double[] priority = new double[groups.size()];
		for (int i = 0; i < groups.size(); i++) {
			order.add(i);
			priority[i] = groups.get(i).volume() * (0.90 + random.nextDouble() * 0.20);
		}
		order.sort(Comparator.<Integer>comparingDouble(index -> -priority[index])
				.thenComparingLong(index -> -groups.get(index).largestBox())
				.thenComparing(index -> groups.get(index).stableKey()));
		return order;
	}
}
