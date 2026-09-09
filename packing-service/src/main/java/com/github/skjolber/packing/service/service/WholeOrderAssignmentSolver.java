package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;

/**
 * Assigns each house bill to exactly one physical container. It deliberately
 * does not use core's BoxItemGroup iterator: grouping exists only at the
 * service-level assignment stage, while each container is still packed with
 * ordinary BoxItems.
 */
final class WholeOrderAssignmentSolver {

	private static final int GREEDY_SEEDS = 24;
	private static final int MAX_CANDIDATES = 256;
	private static final long SYSTEMATIC_NODE_LIMIT = 2_000_000L;

	List<Candidate> candidates(PackingPlan plan) {
		List<Container> containers = expandContainers(plan.containerItems());
		List<OrderGroup> groups = group(plan.boxItems());
		if (containers.size() < 2 || groups.isEmpty()) {
			return List.of();
		}

		List<Candidate> candidates = new ArrayList<>();
		Set<String> signatures = new HashSet<>();
		List<int[]> greedyAssignments = new ArrayList<>();
		for (int seed = 0; seed < GREEDY_SEEDS && candidates.size() < MAX_CANDIDATES; seed++) {
			int[] assignment = greedy(groups, containers, seed);
			if (assignment != null) {
				addCandidate(candidates, signatures, groups, containers.size(), assignment, "greedy-" + seed);
				greedyAssignments.add(assignment);
			}
		}
		for (int[] assignment : greedyAssignments) {
			addLocalMoves(candidates, signatures, groups, containers, assignment);
		}

		if (candidates.size() < MAX_CANDIDATES) {
			addSystematicCandidates(candidates, signatures, groups, containers);
		}
		return candidates;
	}

	private static List<Container> expandContainers(List<ContainerItem> containerItems) {
		List<Container> result = new ArrayList<>();
		for (ContainerItem item : containerItems) {
			for (int i = 0; i < item.getCount(); i++) {
				result.add(item.getContainer());
			}
		}
		return result;
	}

	private static List<OrderGroup> group(List<BoxItem> boxItems) {
		Map<String, List<BoxItem>> byHouseBill = new LinkedHashMap<>();
		int anonymous = 0;
		for (BoxItem item : boxItems) {
			String houseBsId = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			if (houseBsId == null || houseBsId.isBlank()) {
				houseBsId = "__anonymous_" + anonymous++;
			}
			byHouseBill.computeIfAbsent(houseBsId, ignored -> new ArrayList<>()).add(item);
		}

		List<OrderGroup> result = new ArrayList<>();
		for (Map.Entry<String, List<BoxItem>> entry : byHouseBill.entrySet()) {
			long volume = entry.getValue().stream().mapToLong(BoxItem::getVolume).sum();
			long weight = entry.getValue().stream().mapToLong(BoxItem::getWeight).sum();
			long largestBox = entry.getValue().stream()
					.mapToLong(item -> item.getBox().getVolume())
					.max()
					.orElse(0L);
			result.add(new OrderGroup(entry.getKey(), List.copyOf(entry.getValue()), volume, weight, largestBox));
		}
		return result;
	}

	private static int[] greedy(List<OrderGroup> groups, List<Container> containers, int seed) {
		Random random = new Random(31L * seed + 17L);
		List<Integer> order = new ArrayList<>();
		double[] priority = new double[groups.size()];
		for (int i = 0; i < groups.size(); i++) {
			order.add(i);
			priority[i] = groups.get(i).volume() * (0.90 + random.nextDouble() * 0.20);
		}
		order.sort(Comparator.<Integer>comparingDouble(index -> -priority[index])
				.thenComparingLong(index -> -groups.get(index).largestBox()));

		long[] usedVolume = new long[containers.size()];
		long[] usedWeight = new long[containers.size()];
		int[] assignment = new int[groups.size()];
		Arrays.fill(assignment, -1);
		for (int groupIndex : order) {
			OrderGroup group = groups.get(groupIndex);
			int best = -1;
			double bestScore = Double.POSITIVE_INFINITY;
			for (int containerIndex = 0; containerIndex < containers.size(); containerIndex++) {
				Container container = containers.get(containerIndex);
				if (!fits(group, container, usedVolume[containerIndex], usedWeight[containerIndex])) {
					continue;
				}
				double volumeFill = (usedVolume[containerIndex] + group.volume()) / (double) container.getMaxLoadVolume();
				double weightFill = container.getMaxLoadWeight() == 0 ? 0.0
						: (usedWeight[containerIndex] + group.weight()) / (double) container.getMaxLoadWeight();
				// Balanced largest-first is a better 3D starting point than filling one
				// container to its scalar capacity before opening the next one.
				double score = volumeFill + 0.15 * weightFill + random.nextDouble() * 0.01;
				if (score < bestScore) {
					bestScore = score;
					best = containerIndex;
				}
			}
			if (best < 0) {
				return null;
			}
			assignment[groupIndex] = best;
			usedVolume[best] += group.volume();
			usedWeight[best] += group.weight();
		}
		return assignment;
	}

	private static void addLocalMoves(List<Candidate> result, Set<String> signatures, List<OrderGroup> groups,
			List<Container> containers, int[] base) {
		int limit = Math.min(MAX_CANDIDATES, result.size() + 6);
		long[] volumes = loads(groups, base, containers.size(), true);
		long[] weights = loads(groups, base, containers.size(), false);
		for (int groupIndex = 0; groupIndex < groups.size() && result.size() < limit; groupIndex++) {
			int from = base[groupIndex];
			OrderGroup group = groups.get(groupIndex);
			for (int to = 0; to < containers.size() && result.size() < limit; to++) {
				if (to == from || !fits(group, containers.get(to), volumes[to], weights[to])) {
					continue;
				}
				int[] moved = base.clone();
				moved[groupIndex] = to;
				addCandidate(result, signatures, groups, containers.size(), moved, "local-move");
			}
		}

		for (int left = 0; left < groups.size() && result.size() < limit; left++) {
			for (int right = left + 1; right < groups.size() && result.size() < limit; right++) {
				int leftContainer = base[left];
				int rightContainer = base[right];
				if (leftContainer == rightContainer) {
					continue;
				}
				OrderGroup leftGroup = groups.get(left);
				OrderGroup rightGroup = groups.get(right);
				long newLeftVolume = volumes[leftContainer] - leftGroup.volume() + rightGroup.volume();
				long newRightVolume = volumes[rightContainer] - rightGroup.volume() + leftGroup.volume();
				long newLeftWeight = weights[leftContainer] - leftGroup.weight() + rightGroup.weight();
				long newRightWeight = weights[rightContainer] - rightGroup.weight() + leftGroup.weight();
				if (!withinCapacity(rightGroup, containers.get(leftContainer), newLeftVolume, newLeftWeight)
						|| !withinCapacity(leftGroup, containers.get(rightContainer), newRightVolume, newRightWeight)) {
					continue;
				}
				int[] swapped = base.clone();
				swapped[left] = rightContainer;
				swapped[right] = leftContainer;
				addCandidate(result, signatures, groups, containers.size(), swapped, "local-swap");
			}
		}
	}

	private static long[] loads(List<OrderGroup> groups, int[] assignment, int containerCount, boolean volume) {
		long[] result = new long[containerCount];
		for (int i = 0; i < groups.size(); i++) {
			result[assignment[i]] += volume ? groups.get(i).volume() : groups.get(i).weight();
		}
		return result;
	}

	private static boolean fits(OrderGroup group, Container container, long usedVolume, long usedWeight) {
		return withinCapacity(group, container, usedVolume + group.volume(), usedWeight + group.weight());
	}

	private static boolean withinCapacity(OrderGroup group, Container container, long totalVolume, long totalWeight) {
		if (totalVolume > container.getMaxLoadVolume() || totalWeight > container.getMaxLoadWeight()) {
			return false;
		}
		return group.items().stream().allMatch(item -> container.canLoad(item.getBox()));
	}

	private static void addSystematicCandidates(List<Candidate> result, Set<String> signatures,
			List<OrderGroup> groups, List<Container> containers) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) {
			order.add(i);
		}
		order.sort(Comparator.<Integer>comparingLong(index -> -groups.get(index).volume())
				.thenComparingLong(index -> -groups.get(index).largestBox()));

		SearchState state = new SearchState(result, signatures, groups, containers, order);
		state.search(0);
	}

	private static void addCandidate(List<Candidate> result, Set<String> signatures,
			List<OrderGroup> groups, int containerCount, int[] assignment, String source) {
		String signature = Arrays.toString(assignment);
		if (!signatures.add(signature)) {
			return;
		}
		List<List<BoxItem>> itemsByContainer = new ArrayList<>(containerCount);
		for (int i = 0; i < containerCount; i++) {
			itemsByContainer.add(new ArrayList<>());
		}
		for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
			itemsByContainer.get(assignment[groupIndex]).addAll(groups.get(groupIndex).items());
		}
		result.add(new Candidate(itemsByContainer.stream().map(List::copyOf).toList(), source));
	}

	record Candidate(List<List<BoxItem>> itemsByContainer, String source) {
	}

	private record OrderGroup(String id, List<BoxItem> items, long volume, long weight, long largestBox) {
	}

	private static final class SearchState {

		private final List<Candidate> result;
		private final Set<String> signatures;
		private final List<OrderGroup> groups;
		private final List<Container> containers;
		private final List<Integer> order;
		private final int[] assignment;
		private final long[] volumes;
		private final long[] weights;
		private long nodes;

		private SearchState(List<Candidate> result, Set<String> signatures, List<OrderGroup> groups,
				List<Container> containers, List<Integer> order) {
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
			if (result.size() >= MAX_CANDIDATES || ++nodes > SYSTEMATIC_NODE_LIMIT) {
				return;
			}
			if (depth == order.size()) {
				addCandidate(result, signatures, groups, containers.size(), assignment.clone(), "systematic");
				return;
			}

			int groupIndex = order.get(depth);
			OrderGroup group = groups.get(groupIndex);
			List<Integer> destinations = new ArrayList<>();
			for (int i = 0; i < containers.size(); i++) {
				destinations.add(i);
			}
			destinations.sort(Comparator.comparingDouble(i ->
					(containerFillAfter(i, group))));
			Set<String> equivalentLoads = new HashSet<>();
			for (int containerIndex : destinations) {
				Container container = containers.get(containerIndex);
				String loadSignature = container.getLoadDx() + "x" + container.getLoadDy() + "x" + container.getLoadDz()
						+ ":" + container.getMaxLoadVolume() + ":" + container.getMaxLoadWeight()
						+ ":" + volumes[containerIndex] + ":" + weights[containerIndex];
				if (!equivalentLoads.add(loadSignature)
						|| !fits(group, container, volumes[containerIndex], weights[containerIndex])) {
					continue;
				}
				assignment[groupIndex] = containerIndex;
				volumes[containerIndex] += group.volume();
				weights[containerIndex] += group.weight();
				search(depth + 1);
				volumes[containerIndex] -= group.volume();
				weights[containerIndex] -= group.weight();
				assignment[groupIndex] = -1;
				if (result.size() >= MAX_CANDIDATES || nodes > SYSTEMATIC_NODE_LIMIT) {
					return;
				}
			}
		}

		private double containerFillAfter(int containerIndex, OrderGroup group) {
			Container container = containers.get(containerIndex);
			double fill = (volumes[containerIndex] + group.volume()) / (double) container.getMaxLoadVolume();
			// Descending fill implements best-fit while remaining deterministic.
			return -fill;
		}
	}
}
