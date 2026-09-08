package com.github.skjolber.packing.service.service;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxItemGroup;
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

	PackagerResult pack(PackingPlan plan) {
		if (plan.containerItems().isEmpty() || plan.boxItems().isEmpty()) {
			return null;
		}

		PackagerResult best = null;
		best = better(best, packOrientation(plan, false));
		best = better(best, packOrientation(plan, true));
		return best;
	}

	String failureReason(PackingPlan plan) {
		List<Container> containers = plan.containerItems().stream()
				.map(ContainerItem::getContainer).toList();
		for (BoxItemGroup group : houseBillGroups(plan.boxItems())) {
			boolean anyDimensionFits = group.getItems().stream()
					.allMatch(item -> containers.stream().anyMatch(c -> item.getBox().fitsInside(c)));
			if (!anyDimensionFits) {
				return "货物尺寸超过所有可用柜型，无法装入单个柜子，houseBsId=" + group.getId();
			}
			boolean anyCapacityFits = containers.stream().anyMatch(c ->
					group.getVolume() <= c.getMaxLoadVolume()
							&& group.getWeight() <= c.getMaxLoadWeight());
			if (!anyCapacityFits) {
				return "同一分单的总体积或重量超过所有单柜能力，无法整票装入，houseBsId=" + group.getId();
			}
		}
		return "没有有效装箱方案";
	}

	private static PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth) {
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
					.build()));
			return best;
		}
		best = better(best, tryPack(plan, containers, "LAFF" + (swapLengthWidth ? "-SWAPPED" : ""), LargestAreaFitFirstPackager.newBuilder().build()));
		best = better(best, tryPack(plan, containers, "FastLAFF" + (swapLengthWidth ? "-SWAPPED" : ""), FastLargestAreaFitFirstPackager.newBuilder().build()));
		best = better(best, tryPack(plan, containers, "Plain" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder().build()));
		return best;
	}

	private static PackagerResult tryPack(PackingPlan plan, List<ContainerItem> containers, String label, Packager<? extends AbstractPackagerResultBuilder<?>> packager) {
		try {
			List<BoxItemGroup> houseBillGroups = houseBillGroups(plan.boxItems());
			PackagerResult result = packager
					.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(totalContainerCount(containers))
					.withBoxItemGroups(houseBillGroups)
					.build();

			System.out.println("packing-service " + label + " success=" + result.isSuccess() + " containerCount=" + result.size());
			boolean valid = result.isSuccess() && isHouseBillPackingValid(result)
					&& BottomRuleSupport.isValid(result) && NoPressRuleSupport.isValid(result);
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

	private static List<BoxItemGroup> houseBillGroups(List<BoxItem> boxItems) {
		Map<String, List<BoxItem>> grouped = new LinkedHashMap<>();
		for (BoxItem item : boxItems) {
			String houseBsId = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			List<BoxItem> groupItems = grouped.computeIfAbsent(houseBsId, ignored -> new ArrayList<>());
			// Group iterators use one index per BoxItem entry. Expand aggregated
			// quantities so their index space matches their permutation space.
			for (int i = 0; i < item.getCount(); i++) {
				// Each BoxItem must also own a distinct Box because Box keeps a
				// mutable back-reference to its BoxItem.
				groupItems.add(new BoxItem(item.getBox().clone(), 1));
			}
		}
		List<BoxItemGroup> result = new ArrayList<>();
		for (Map.Entry<String, List<BoxItem>> entry : grouped.entrySet()) {
			result.add(new BoxItemGroup(entry.getKey(), entry.getValue()));
		}
		return result;
	}

	private static boolean isHouseBillPackingValid(PackagerResult result) {
		Map<String, String> houseBillContainers = new LinkedHashMap<>();
		for (Container container : result.getContainers()) {
			for (com.github.skjolber.packing.api.Placement placement : container.getStack().getPlacements()) {
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				String previousContainer = houseBillContainers.putIfAbsent(houseBsId, container.getId());
				if (previousContainer != null && !previousContainer.equals(container.getId())) {
					System.out.println("packing-service rejected split house bill houseBsId=" + houseBsId
							+ " containers=" + previousContainer + "," + container.getId());
					return false;
				}
			}
		}
		return true;
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

}
