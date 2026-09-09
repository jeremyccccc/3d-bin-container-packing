package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Packager;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.packer.AbstractPackagerResultBuilder;
import com.github.skjolber.packing.packer.laff.FastLargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.laff.LargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.plain.PlainPackager;
import com.github.skjolber.packing.packer.plain.PlainPlacementComparator;
import com.github.skjolber.packing.comparator.VolumeThenWeightBoxItemComparator;

@Component
class PackingEngine {
	private static final long WHOLE_ORDER_SEARCH_MILLIS = 180_000L;
	private static final long BETTER_SOLUTION_SEARCH_MILLIS = 15_000L;

	private final WholeOrderAssignmentSolver assignmentSolver = new WholeOrderAssignmentSolver();

	PackagerResult pack(PackingPlan plan) {
		if (plan.containerItems().isEmpty() || plan.boxItems().isEmpty()) {
			return null;
		}

		// Preserve the established path. A one-container result already satisfies
		// the whole-order rule without invoking any grouping implementation.
		PackagerResult original = packFlat(plan, 0L);
		if (totalContainerCount(plan.containerItems()) == 1
				|| original != null && original.isSuccess() && !splitsHouseBills(original)) {
			return original;
		}

		return packWholeOrders(plan);
	}

	private PackagerResult packWholeOrders(PackingPlan plan) {
		long deadline = System.currentTimeMillis() + WHOLE_ORDER_SEARCH_MILLIS;
		List<ContainerItem> physicalContainers = expandContainerItems(plan.containerItems());
		List<WholeOrderAssignmentSolver.Candidate> candidates = assignmentSolver.candidates(plan);
		PackagerResult best = null;
		int attempted = 0;
		int successful = 0;
		long stopAt = deadline;
		for (WholeOrderAssignmentSolver.Candidate candidate : candidates) {
			if (System.currentTimeMillis() >= stopAt) {
				break;
			}
			attempted++;
			List<Container> packedContainers = new ArrayList<>();
			long duration = 0L;
			boolean success = true;
			for (int i = 0; i < physicalContainers.size(); i++) {
				List<com.github.skjolber.packing.api.BoxItem> items = candidate.itemsByContainer().get(i);
				if (items.isEmpty()) {
					continue;
				}
				PackingPlan containerPlan = new PackingPlan(
						plan.requestedContainers(),
						List.of(physicalContainers.get(i)),
						items,
						plan.cargoLines(),
						plan.warnings());
				PackagerResult packed = packFlat(containerPlan, deadline);
				if (packed == null || !packed.isSuccess() || packed.size() != 1) {
					success = false;
					break;
				}
				packedContainers.add(packed.get(0));
				duration += packed.getDuration();
			}
			if (success) {
				PackagerResult packed = new PackagerResult(packedContainers, duration, false);
				if (!splitsHouseBills(packed)) {
					successful++;
					System.out.println("packing-service whole-order success source=" + candidate.source()
							+ " attempt=" + attempted + " containerCount=" + packed.size());
					best = better(best, packed);
					if (successful == 1) {
						stopAt = Math.min(deadline, System.currentTimeMillis() + BETTER_SOLUTION_SEARCH_MILLIS);
					}
				}
			}
		}
		System.out.println("packing-service whole-order candidates=" + candidates.size()
				+ " attempted=" + attempted + " successful=" + successful + " success=" + (best != null));
		return best;
	}

	private static List<ContainerItem> expandContainerItems(List<ContainerItem> items) {
		List<ContainerItem> result = new ArrayList<>();
		for (ContainerItem item : items) {
			for (int i = 0; i < item.getCount(); i++) {
				result.add(new ContainerItem(item.getContainer(), 1));
			}
		}
		return result;
	}

	private static boolean splitsHouseBills(PackagerResult result) {
		Map<String, Integer> containersByHouseBill = new HashMap<>();
		for (int containerIndex = 0; containerIndex < result.getContainers().size(); containerIndex++) {
			Container container = result.getContainers().get(containerIndex);
			for (com.github.skjolber.packing.api.Placement placement : container.getStack().getPlacements()) {
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				if (houseBsId == null) {
					continue;
				}
				Integer previous = containersByHouseBill.putIfAbsent(houseBsId, containerIndex);
				if (previous != null && previous != containerIndex) {
					return true;
				}
			}
		}
		return false;
	}

	private static PackagerResult packFlat(PackingPlan plan, long deadline) {
		PackagerResult best = null;
		best = better(best, packOrientation(plan, false, deadline));
		best = better(best, packOrientation(plan, true, deadline));
		return best;
	}

	private static PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth, long deadline) {
		List<ContainerItem> containers = swapLengthWidth ? swappedContainers(plan.containerItems()) : plan.containerItems();
		PackagerResult best = null;
		if (hasBusinessRule(plan)) {
			boolean hasDoorSideRule = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			best = better(best, tryPack(plan, containers, "Plain-Rules" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new PlainPlacementComparator(),
							hasDoorSideRule ? new RuleBoxItemComparator() : VolumeThenWeightBoxItemComparator.getInstance(),
							false,
							hasDoorSideRule))
					.build(), deadline));
			return best;
		}
		best = better(best, tryPack(plan, containers, "LAFF" + (swapLengthWidth ? "-SWAPPED" : ""), LargestAreaFitFirstPackager.newBuilder().build(), deadline));
		best = better(best, tryPack(plan, containers, "FastLAFF" + (swapLengthWidth ? "-SWAPPED" : ""), FastLargestAreaFitFirstPackager.newBuilder().build(), deadline));
		best = better(best, tryPack(plan, containers, "Plain" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder().build(), deadline));
		return best;
	}

	private static PackagerResult tryPack(PackingPlan plan, List<ContainerItem> containers, String label,
			Packager<? extends AbstractPackagerResultBuilder<?>> packager, long deadline) {
		try {
			AbstractPackagerResultBuilder<?> builder = packager
					.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(totalContainerCount(containers))
					.withBoxItems(cloneBoxItems(plan.boxItems()));
			if (deadline > 0L) {
				builder.withDeadline(deadline);
			}
			PackagerResult result = builder.build();

			System.out.println("packing-service " + label + " success=" + result.isSuccess() + " containerCount=" + result.size());
			boolean valid = result.isSuccess() && BottomRuleSupport.isValid(result) && NoPressRuleSupport.isValid(result);
			boolean hasDoorSideRule = DoorSideRuleSupport.hasDoorSideRule(plan.boxItems());
			if (valid && hasDoorSideRule) {
				DoorSideRuleSupport.mirrorToDoorSide(result);
				valid = DoorSideRuleSupport.isValid(result);
			}
			return valid ? result : null;
		} finally {
			try {
				packager.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static boolean hasBusinessRule(PackingPlan plan) {
		return plan.boxItems().stream().anyMatch(item ->
				BottomRuleSupport.hasBottomRule(item.getBox())
						|| NoPressRuleSupport.hasNoPressRule(item.getBox())
						|| DoorSideRuleSupport.hasDoorSideRule(item.getBox()));
	}

	private static List<ContainerItem> swappedContainers(List<ContainerItem> source) {
		return source.stream().map(item -> {
			Container original = item.getContainer();
			Container swapped = Container.newBuilder()
					.withId(original.getId())
					.withDescription(original.getDescription() + "-SWAPPED")
					.withSize(original.getDy(), original.getDx(), original.getDz())
					.withEmptyWeight(original.getEmptyWeight())
					.withMaxLoadWeight(original.getMaxLoadWeight())
					.build();
			return new ContainerItem(swapped, item.getCount());
		}).toList();
	}

	private static PackagerResult better(PackagerResult current, PackagerResult candidate) {
		if (candidate == null) {
			return current;
		}
		if (current == null || candidate.size() < current.size()) {
			return candidate;
		}
		if (candidate.size() == current.size() && totalLoadVolume(candidate) > totalLoadVolume(current)) {
			return candidate;
		}
		if (candidate.size() == current.size() && totalLoadVolume(candidate) == totalLoadVolume(current)
				&& DoorSideRuleSupport.score(candidate).isBetterThan(DoorSideRuleSupport.score(current))) {
			return candidate;
		}
		return current;
	}

	private static int totalContainerCount(List<ContainerItem> containerItems) {
		int count = 0;
		for (ContainerItem item : containerItems) {
			count += item.getCount();
		}
		return count;
	}

	private static long totalLoadVolume(PackagerResult result) {
		return result.getContainers().stream().mapToLong(c -> c.getLoadVolume()).sum();
	}

	private static List<com.github.skjolber.packing.api.BoxItem> cloneBoxItems(List<com.github.skjolber.packing.api.BoxItem> items) {
		return items.stream().map(com.github.skjolber.packing.api.BoxItem::clone).toList();
	}
}
