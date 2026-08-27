package com.github.skjolber.packing.service.service;

import java.util.List;

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

	PackagerResult pack(PackingPlan plan) {
		if (plan.containerItems().isEmpty() || plan.boxItems().isEmpty()) {
			return null;
		}

		PackagerResult best = null;
		best = better(best, packOrientation(plan, false));
		best = better(best, packOrientation(plan, true));
		return best;
	}

	private static PackagerResult packOrientation(PackingPlan plan, boolean swapLengthWidth) {
		List<ContainerItem> containers = swapLengthWidth ? swappedContainers(plan.containerItems()) : plan.containerItems();
		PackagerResult best = null;
		if (hasBottomRule(plan)) {
			PlainPackager packager = PlainPackager.newBuilder()
					.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
							new PlainPlacementComparator(), VolumeThenWeightBoxItemComparator.getInstance(), false))
					.build();
			return tryPack(plan, containers, "Plain-Bottom" + (swapLengthWidth ? "-SWAPPED" : ""), packager);
		}
		best = better(best, tryPack(plan, containers, "LAFF" + (swapLengthWidth ? "-SWAPPED" : ""), LargestAreaFitFirstPackager.newBuilder().build()));
		best = better(best, tryPack(plan, containers, "FastLAFF" + (swapLengthWidth ? "-SWAPPED" : ""), FastLargestAreaFitFirstPackager.newBuilder().build()));
		best = better(best, tryPack(plan, containers, "Plain" + (swapLengthWidth ? "-SWAPPED" : ""), PlainPackager.newBuilder().build()));
		return best;
	}

	private static PackagerResult tryPack(PackingPlan plan, List<ContainerItem> containers, String label, Packager<? extends AbstractPackagerResultBuilder<?>> packager) {
		try {
			PackagerResult result = packager
					.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(totalContainerCount(containers))
					.withBoxItems(cloneBoxItems(plan.boxItems()))
					.build();

			System.out.println("packing-service " + label + " success=" + result.isSuccess() + " containerCount=" + result.size());
			boolean valid = result.isSuccess() && BottomRuleSupport.isValid(result);
			return valid ? result : null;
		} finally {
			try {
				packager.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static boolean hasBottomRule(PackingPlan plan) {
		return plan.boxItems().stream().anyMatch(item -> BottomRuleSupport.hasBottomRule(item.getBox()));
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
