package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.Packager;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.Stack;
import com.github.skjolber.packing.api.point.Point;
import com.github.skjolber.packing.comparator.LargestAreaBoxItemComparator;
import com.github.skjolber.packing.comparator.VolumeThenWeightBoxItemComparator;
import com.github.skjolber.packing.packer.AbstractPackagerResultBuilder;
import com.github.skjolber.packing.packer.laff.FastLargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.laff.LargestAreaFitFirstPackager;
import com.github.skjolber.packing.packer.plain.PlainPackager;
import com.github.skjolber.packing.packer.plain.PlainPlacementComparator;
import com.github.skjolber.packing.ep.points3d.DefaultPointCalculator3D;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

final class SingleContainerPackingOracle {

	PackagerResult insertIntoExisting(Container existing, BoxItem incoming, long deadline) {
		if (System.currentTimeMillis() >= deadline
				|| existing.getLoadWeight() + incoming.getWeight() > existing.getMaxLoadWeight()
				|| existing.getLoadVolume() + incoming.getVolume() > existing.getMaxLoadVolume()) {
			return null;
		}

		List<Point> points;
		try {
			points = calculateFreePoints(existing);
		} catch (RuntimeException e) {
			return null;
		}
		points.sort(Comparator.comparingInt(Point::getMinZ)
				.thenComparingInt(Point::getMinY)
				.thenComparingInt(Point::getMinX)
				.thenComparingLong(Point::getVolume));
		for (Point point : points) {
			for (BoxStackValue stackValue : incoming.getBox().getStackValues()) {
				if (!point.fits3D(stackValue)) {
					continue;
				}
				Placement placement = new Placement(stackValue, new DefaultPoint3D(
						point.getMinX(), point.getMinY(), point.getMinZ(),
						point.getMinX() + stackValue.getDx() - 1,
						point.getMinY() + stackValue.getDy() - 1,
						point.getMinZ() + stackValue.getDz() - 1));
				Container merged = merge(existing, placement);
				PackagerResult result = new PackagerResult(List.of(merged), 0, false);
				if (isValidMerged(result)) {
					return result;
				}
			}
		}
		return null;
	}

	PackagerResult pack(List<ContainerItem> containers, List<BoxItem> boxes, int maxContainers, long deadline) {
		if (containers.isEmpty() || boxes.isEmpty() || System.currentTimeMillis() >= deadline) {
			return null;
		}

		PackagerResult best = null;
		best = better(best, packOrientation(containers, boxes, maxContainers, deadline, false));
		best = better(best, packOrientation(containers, boxes, maxContainers, deadline, true));
		return best;
	}

	private static PackagerResult packOrientation(List<ContainerItem> containers, List<BoxItem> boxes,
			int maxContainers, long deadline, boolean swapLengthWidth) {
		List<ContainerItem> orientedContainers = swapLengthWidth ? swappedContainers(containers) : containers;
		boolean hasBottomRule = boxes.stream().anyMatch(item -> BottomRuleSupport.hasBottomRule(item.getBox()));
		PackagerResult best = null;

		if (hasBottomRule) {
			best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline, bottomPlain(false)));
			best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline, bottomPlain(true)));
			return best;
		}

		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(VolumeThenWeightBoxItemComparator.getInstance())));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(new LargestAreaBoxItemComparator())));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(true))));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(new LargestAreaBoxItemComparator(), floorFirst(true))));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(VolumeThenWeightBoxItemComparator.getInstance(), floorFirst(false))));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(new LargestAreaBoxItemComparator(), floorFirst(false))));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(VolumeThenWeightBoxItemComparator.getInstance(), narrowAxisFirst(swapLengthWidth))));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				supportedPlain(new LargestAreaBoxItemComparator(), narrowAxisFirst(swapLengthWidth))));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				FastLargestAreaFitFirstPackager.newBuilder().build()));
		best = better(best, tryPack(orientedContainers, boxes, maxContainers, deadline,
				LargestAreaFitFirstPackager.newBuilder().build()));
		return best;
	}

	private static PlainPackager bottomPlain(boolean largestAreaFirst) {
		return supportedPlain(largestAreaFirst
				? new LargestAreaBoxItemComparator()
				: VolumeThenWeightBoxItemComparator.getInstance());
	}

	private static PlainPackager supportedPlain(Comparator<BoxItem> boxItemComparator) {
		return supportedPlain(boxItemComparator, new PlainPlacementComparator());
	}

	private static PlainPackager supportedPlain(Comparator<BoxItem> boxItemComparator,
			Comparator<Placement> placementComparator) {
		return PlainPackager.newBuilder()
				.withPlacementControlsBuilderFactory(() -> new BottomPlacementControlsBuilder(
						placementComparator,
						boxItemComparator,
						true))
				.build();
	}

	private static Comparator<Placement> floorFirst(boolean yBeforeX) {
		Comparator<Placement> lowerZ = Comparator.comparingInt(Placement::getAbsoluteZ).reversed();
		Comparator<Placement> lowerX = Comparator.comparingInt(Placement::getAbsoluteX).reversed();
		Comparator<Placement> lowerY = Comparator.comparingInt(Placement::getAbsoluteY).reversed();
		Comparator<Placement> position = yBeforeX
				? lowerZ.thenComparing(lowerY).thenComparing(lowerX)
				: lowerZ.thenComparing(lowerX).thenComparing(lowerY);
		return position.thenComparingLong(placement -> placement.getStackValue().getArea());
	}

	private static Comparator<Placement> narrowAxisFirst(boolean narrowX) {
		Comparator<Placement> lowerZ = Comparator.comparingInt(Placement::getAbsoluteZ).reversed();
		Comparator<Placement> lowerLongAxis = Comparator.comparingInt(
				narrowX ? Placement::getAbsoluteY : Placement::getAbsoluteX).reversed();
		Comparator<Placement> lowerNarrowAxis = Comparator.comparingInt(
				narrowX ? Placement::getAbsoluteX : Placement::getAbsoluteY).reversed();
		Comparator<Placement> smallerNarrowDimension = Comparator.comparingInt((Placement placement) -> narrowX
				? placement.getStackValue().getDx()
				: placement.getStackValue().getDy()).reversed();
		return lowerZ
				.thenComparing(lowerLongAxis)
				.thenComparing(lowerNarrowAxis)
				.thenComparing(smallerNarrowDimension)
				.thenComparingLong(placement -> placement.getStackValue().getArea());
	}

	private static List<Point> calculateFreePoints(Container existing) {
		int obstacleCount = existing.getStack().size();
		DefaultPointCalculator3D calculator = new DefaultPointCalculator3D(false, Math.max(64, obstacleCount * 64));
		calculator.clearToSize(existing.getLoadDx(), existing.getLoadDy(), existing.getLoadDz());
		for (Placement placement : existing.getStack().getPlacements()) {
			if (!calculator.addObstacle(placement)) {
				return List.of();
			}
		}
		return new ArrayList<>(calculator.getAll());
	}

	private static Container merge(Container existing, Placement inserted) {
		Stack stack = new Stack();
		stack.addAll(existing.getStack().getPlacements());
		stack.add(inserted);
		return Container.newBuilder()
				.withId(existing.getId())
				.withDescription(existing.getDescription())
				.withSize(existing.getDx(), existing.getDy(), existing.getDz())
				.withLoadSize(existing.getLoadDx(), existing.getLoadDy(), existing.getLoadDz())
				.withEmptyWeight(existing.getEmptyWeight())
				.withMaxLoadWeight(existing.getMaxLoadWeight())
				.withStack(stack)
				.build();
	}

	private static boolean isValidMerged(PackagerResult result) {
		Container container = result.get(0);
		List<Placement> placements = container.getStack().getPlacements();
		for (int i = 0; i < placements.size(); i++) {
			Placement current = placements.get(i);
			if (current.getAbsoluteX() < 0 || current.getAbsoluteY() < 0 || current.getAbsoluteZ() < 0
					|| current.getAbsoluteEndX() >= container.getLoadDx()
					|| current.getAbsoluteEndY() >= container.getLoadDy()
					|| current.getAbsoluteEndZ() >= container.getLoadDz()) {
				return false;
			}
			for (int j = i + 1; j < placements.size(); j++) {
				if (current.intersects3D(placements.get(j))) {
					return false;
				}
			}
		}
		return PlacementSupport.isValid(result) && BottomRuleSupport.isValid(result);
	}

	private static PackagerResult tryPack(List<ContainerItem> containers, List<BoxItem> boxes, int maxContainers,
			long deadline, Packager<? extends AbstractPackagerResultBuilder<?>> packager) {
		try {
			if (System.currentTimeMillis() >= deadline) {
				return null;
			}
			PackagerResult result = packager.newResultBuilder()
					.withContainerItems(containers)
					.withMaxContainerCount(maxContainers)
					.withBoxItems(cloneBoxItems(boxes))
					.withDeadline(deadline)
					.build();
			return result.isSuccess()
					&& PlacementSupport.isValid(result)
					&& BottomRuleSupport.isValid(result) ? result : null;
		} finally {
			try {
				packager.close();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private static PackagerResult better(PackagerResult current, PackagerResult candidate) {
		if (candidate == null) {
			return current;
		}
		return current == null || PackingPlanComparator.INSTANCE.compare(candidate, current) > 0 ? candidate : current;
	}

	private static List<ContainerItem> swappedContainers(List<ContainerItem> source) {
		List<ContainerItem> result = new ArrayList<>(source.size());
		for (ContainerItem item : source) {
			Container original = item.getContainer();
			Container swapped = Container.newBuilder()
					.withId(original.getId())
					.withDescription(original.getDescription() + "-SWAPPED")
					.withSize(original.getDy(), original.getDx(), original.getDz())
					.withEmptyWeight(original.getEmptyWeight())
					.withMaxLoadWeight(original.getMaxLoadWeight())
					.build();
			result.add(new ContainerItem(swapped, item.getCount()));
		}
		return result;
	}

	static List<BoxItem> cloneBoxItems(List<BoxItem> items) {
		return items.stream().map(BoxItem::clone).toList();
	}
}
