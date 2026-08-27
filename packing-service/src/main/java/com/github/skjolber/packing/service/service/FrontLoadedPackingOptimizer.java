package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

final class FrontLoadedPackingOptimizer {

	private final SingleContainerPackingOracle oracle;

	FrontLoadedPackingOptimizer(SingleContainerPackingOracle oracle) {
		this.oracle = oracle;
	}

	PackagerResult optimize(PackingPlan plan, PackagerResult initial, OptimizationBudget budget) {
		if (initial == null || !initial.isSuccess() || initial.size() < 2) {
			return initial;
		}

		int usedContainers = initial.size();
		List<ContainerItem> containers = expandContainers(plan.containerItems(), usedContainers);
		if (containers.size() < usedContainers) {
			return initial;
		}

		List<BoxItem> boxes = SingleContainerPackingOracle.cloneBoxItems(plan.boxItems());
		PackagerResult local = improveLocally(containers, boxes, initial, budget);
		PackagerResult peeled = optimizeFrom(containers, boxes, budget);
		PackagerResult optimized = better(local, peeled);
		if (!budget.isExpired()) {
			optimized = improveLocally(containers, boxes, optimized, budget);
		}
		if (PackingPlanComparator.INSTANCE.compare(optimized, initial) <= 0) {
			return initial;
		}
		System.out.println("packing-service optimized candidates=" + budget.evaluatedCandidates()
				+ " firstLoadVolume=" + initial.get(0).getLoadVolume() + "->" + optimized.get(0).getLoadVolume());
		return optimized;
	}

	private PackagerResult improveLocally(List<ContainerItem> containers, List<BoxItem> boxes,
			PackagerResult initial, OptimizationBudget budget) {
		PackagerResult current = initial;
		boolean improved;
		do {
			improved = false;
			int[] firstCounts = countsInFirstContainer(current, boxes);
			List<Integer> incoming = indexesByVolume(boxes, firstCounts, false);
			List<Integer> outgoing = indexesByVolume(boxes, firstCounts, true);
			PackagerResult best = current;

			for (int incomingIndex : incoming) {
				if (!budget.tryAcquireCandidate()) {
					return current;
				}
				int[] moved = firstCounts.clone();
				moved[incomingIndex]++;
				PackagerResult inserted = oracle.insertIntoExisting(current.get(0),
						new BoxItem(boxes.get(incomingIndex).getBox(), 1, boxes.get(incomingIndex).getIndex()),
						budget.deadline());
				if (inserted != null) {
					best = better(best, combineWithRepackedTail(containers, boxes, moved, inserted, budget));
				}
				best = better(best, evaluateAllocation(containers, boxes, moved, budget));
			}

			for (int incomingIndex : incoming) {
				for (int outgoingIndex : outgoing) {
					if (boxes.get(incomingIndex).getBox().getVolume() <= boxes.get(outgoingIndex).getBox().getVolume()) {
						continue;
					}
					if (!budget.tryAcquireCandidate()) {
						return best;
					}
					int[] swapped = firstCounts.clone();
					swapped[incomingIndex]++;
					swapped[outgoingIndex]--;
					best = better(best, evaluateAllocation(containers, boxes, swapped, budget));
				}
			}

			if (PackingPlanComparator.INSTANCE.compare(best, current) > 0) {
				current = best;
				improved = true;
			}
		} while (improved && !budget.isExpired());
		return current;
	}

	private PackagerResult evaluateAllocation(List<ContainerItem> containers, List<BoxItem> boxes,
			int[] firstCounts, OptimizationBudget budget) {
		List<BoxItem> firstBoxes = select(boxes, firstCounts, true);
		PackagerResult first = oracle.pack(List.of(containers.get(0)), firstBoxes, 1, budget.deadline());
		if (first == null) {
			return null;
		}

		List<BoxItem> remaining = select(boxes, firstCounts, false);
		if (remaining.isEmpty()) {
			return first;
		}
		List<ContainerItem> tailContainers = containers.subList(1, containers.size());
		PackagerResult tail = oracle.pack(tailContainers, remaining, tailContainers.size(), budget.deadline());
		return tail != null ? combine(first, tail) : null;
	}

	private PackagerResult combineWithRepackedTail(List<ContainerItem> containers, List<BoxItem> boxes,
			int[] firstCounts, PackagerResult first, OptimizationBudget budget) {
		List<BoxItem> remaining = select(boxes, firstCounts, false);
		if (remaining.isEmpty()) {
			return first;
		}
		List<ContainerItem> tailContainers = containers.subList(1, containers.size());
		PackagerResult tail = oracle.pack(tailContainers, remaining, tailContainers.size(), budget.deadline());
		return tail != null ? combine(first, tail) : null;
	}

	private static int[] countsInFirstContainer(PackagerResult result, List<BoxItem> boxes) {
		Map<String, Integer> indexes = new HashMap<>();
		for (int i = 0; i < boxes.size(); i++) {
			indexes.put(boxes.get(i).getBox().getProperty(PackingMapper.PROP_CARGO_ID), i);
		}

		int[] counts = new int[boxes.size()];
		for (Placement placement : result.get(0).getStack().getPlacements()) {
			String cargoId = placement.getBox().getProperty(PackingMapper.PROP_CARGO_ID);
			Integer index = indexes.get(cargoId);
			if (index != null) {
				counts[index]++;
			}
		}
		return counts;
	}

	private static List<Integer> indexesByVolume(List<BoxItem> boxes, int[] firstCounts, boolean firstContainer) {
		List<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < boxes.size(); i++) {
			int available = firstContainer ? firstCounts[i] : boxes.get(i).getCount() - firstCounts[i];
			if (available > 0) {
				indexes.add(i);
			}
		}
		Comparator<Integer> byVolume = Comparator.comparingLong(index -> boxes.get(index).getBox().getVolume());
		indexes.sort(firstContainer ? byVolume : byVolume.reversed());
		return indexes.size() > 12 ? indexes.subList(0, 12) : indexes;
	}

	private PackagerResult optimizeFrom(List<ContainerItem> containers, List<BoxItem> boxes, OptimizationBudget budget) {
		if (containers.size() == 1) {
			return oracle.pack(containers, boxes, 1, budget.deadline());
		}

		int[] fullCounts = boxes.stream().mapToInt(BoxItem::getCount).toArray();
		long fullVolume = volume(boxes, fullCounts);
		PriorityQueue<Candidate> queue = new PriorityQueue<>(Comparator
				.comparingLong(Candidate::removedVolume)
				.thenComparingInt(Candidate::removedCount)
				.thenComparing(Candidate::key));
		Set<String> seen = new HashSet<>();
		Candidate full = new Candidate(fullCounts, 0L, 0);
		queue.add(full);
		seen.add(full.key());

		while (!queue.isEmpty() && budget.tryAcquireCandidate()) {
			Candidate candidate = queue.remove();
			List<BoxItem> firstBoxes = select(boxes, candidate.counts(), true);
			PackagerResult first = oracle.pack(List.of(containers.get(0)), firstBoxes, 1, budget.deadline());
			if (first != null) {
				List<BoxItem> remaining = select(boxes, candidate.counts(), false);
				if (remaining.isEmpty()) {
					return first;
				} else {
					List<ContainerItem> tailContainers = containers.subList(1, containers.size());
					PackagerResult tailFeasibility = oracle.pack(tailContainers, remaining, tailContainers.size(), budget.deadline());
					if (tailFeasibility != null) {
						PackagerResult optimizedTail = optimizeFrom(tailContainers, remaining, budget);
						return combine(first, optimizedTail != null ? optimizedTail : tailFeasibility);
					}
				}
			}

			expand(queue, seen, candidate, boxes, fullVolume);
		}
		return null;
	}

	private static void expand(PriorityQueue<Candidate> queue, Set<String> seen, Candidate candidate,
			List<BoxItem> boxes, long fullVolume) {
		for (int i = 0; i < candidate.counts().length; i++) {
			if (candidate.counts()[i] == 0) {
				continue;
			}
			int[] next = candidate.counts().clone();
			next[i]--;
			Candidate child = new Candidate(next, fullVolume - volume(boxes, next), candidate.removedCount() + 1);
			if (seen.add(child.key())) {
				queue.add(child);
			}
		}
	}

	private static List<BoxItem> select(List<BoxItem> source, int[] firstCounts, boolean first) {
		List<BoxItem> result = new ArrayList<>();
		for (int i = 0; i < source.size(); i++) {
			BoxItem item = source.get(i);
			int count = first ? firstCounts[i] : item.getCount() - firstCounts[i];
			if (count > 0) {
				result.add(new BoxItem(item.getBox(), count, item.getIndex()));
			}
		}
		return result;
	}

	private static long volume(List<BoxItem> boxes, int[] counts) {
		long volume = 0L;
		for (int i = 0; i < boxes.size(); i++) {
			volume += boxes.get(i).getBox().getVolume() * counts[i];
		}
		return volume;
	}

	private static List<ContainerItem> expandContainers(List<ContainerItem> source, int limit) {
		List<ContainerItem> result = new ArrayList<>(limit);
		for (ContainerItem item : source) {
			for (int i = 0; i < item.getCount() && result.size() < limit; i++) {
				result.add(new ContainerItem(item.getContainer(), 1));
			}
		}
		return result;
	}

	private static PackagerResult combine(PackagerResult first, PackagerResult tail) {
		List<Container> containers = new ArrayList<>(first.size() + tail.size());
		containers.addAll(first.getContainers());
		containers.addAll(tail.getContainers());
		return new PackagerResult(containers, first.getDuration() + tail.getDuration(), first.isTimeout() || tail.isTimeout());
	}

	private static PackagerResult better(PackagerResult current, PackagerResult candidate) {
		if (candidate == null) {
			return current;
		}
		return current == null || PackingPlanComparator.INSTANCE.compare(candidate, current) > 0 ? candidate : current;
	}

	private record Candidate(int[] counts, long removedVolume, int removedCount) {
		String key() {
			return Arrays.toString(counts);
		}
	}
}
