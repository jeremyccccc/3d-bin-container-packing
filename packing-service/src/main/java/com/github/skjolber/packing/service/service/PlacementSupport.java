package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;

/** Validates that every elevated placement has stable support below it. */
final class PlacementSupport {

	static final Policy DEFAULT_POLICY = new Policy(0.85, true, 20, 0.05);

	private PlacementSupport() {
	}

	static Validation validate(PackagerResult result, Policy policy) {
		for (var container : result.getContainers()) {
			List<Placement> placements = container.getStack().getPlacements();
			for (Placement placement : placements) {
				Validation validation = validate(placement, placements, policy);
				if (!validation.valid()) {
					return validation;
				}
			}
		}
		return Validation.supported();
	}

	static Validation validate(Placement candidate, List<Placement> placements, Policy policy) {
		if (candidate.getAbsoluteZ() == 0) {
			return Validation.supported();
		}

		int minX = candidate.getAbsoluteX();
		int maxX = candidate.getAbsoluteEndX() + 1;
		int minY = candidate.getAbsoluteY();
		int maxY = candidate.getAbsoluteEndY() + 1;
		int supportZ = candidate.getAbsoluteZ() - 1;
		List<Rectangle> supports = new ArrayList<>();
		for (Placement placement : placements) {
			if (placement == candidate || placement.getAbsoluteEndZ() != supportZ) {
				continue;
			}
			int overlapMinX = Math.max(minX, placement.getAbsoluteX());
			int overlapMaxX = Math.min(maxX, placement.getAbsoluteEndX() + 1);
			int overlapMinY = Math.max(minY, placement.getAbsoluteY());
			int overlapMaxY = Math.min(maxY, placement.getAbsoluteEndY() + 1);
			if (overlapMinX < overlapMaxX && overlapMinY < overlapMaxY) {
				supports.add(new Rectangle(overlapMinX, overlapMinY, overlapMaxX, overlapMaxY));
			}
		}

		long requiredArea = (long) (maxX - minX) * (maxY - minY);
		long supportedArea = coveredArea(supports);
		double supportRatio = requiredArea == 0L ? 0.0 : supportedArea / (double) requiredArea;
		if (supportRatio + 1.0e-12 < policy.minimumAreaRatio()) {
			return Validation.invalid(candidate, supportRatio, "support-area");
		}

		double centerX = (minX + maxX) / 2.0;
		double centerY = (minY + maxY) / 2.0;
		if (policy.requireCenterSupport() && supports.stream().noneMatch(rectangle -> rectangle.contains(centerX, centerY))) {
			return Validation.invalid(candidate, supportRatio, "center-support");
		}

		int supportMinX = supports.stream().mapToInt(Rectangle::minX).min().orElse(maxX);
		int supportMaxX = supports.stream().mapToInt(Rectangle::maxX).max().orElse(minX);
		int supportMinY = supports.stream().mapToInt(Rectangle::minY).min().orElse(maxY);
		int supportMaxY = supports.stream().mapToInt(Rectangle::maxY).max().orElse(minY);
		int allowedX = policy.maximumOverhang(maxX - minX);
		int allowedY = policy.maximumOverhang(maxY - minY);
		if (supportMinX - minX > allowedX || maxX - supportMaxX > allowedX
				|| supportMinY - minY > allowedY || maxY - supportMaxY > allowedY) {
			return Validation.invalid(candidate, supportRatio, "maximum-overhang");
		}
		return Validation.supported();
	}

	static boolean isAllowed(Point point, BoxStackValue stackValue, List<Placement> placements, Policy policy) {
		return validate(point, stackValue, placements, policy).valid();
	}

	static Validation validate(Point point, BoxStackValue stackValue, List<Placement> placements, Policy policy) {
		return validate(new Placement(stackValue, point), placements, policy);
	}

	private static long coveredArea(List<Rectangle> rectangles) {
		if (rectangles.isEmpty()) {
			return 0L;
		}
		List<Integer> xCoordinates = rectangles.stream()
				.flatMap(rectangle -> List.of(rectangle.minX(), rectangle.maxX()).stream())
				.distinct()
				.sorted()
				.toList();
		long area = 0L;
		for (int i = 0; i < xCoordinates.size() - 1; i++) {
			int slabMinX = xCoordinates.get(i);
			int slabMaxX = xCoordinates.get(i + 1);
			List<Interval> intervals = rectangles.stream()
					.filter(rectangle -> rectangle.minX() <= slabMinX && rectangle.maxX() >= slabMaxX)
					.map(rectangle -> new Interval(rectangle.minY(), rectangle.maxY()))
					.sorted(Comparator.comparingInt(Interval::min).thenComparingInt(Interval::max))
					.toList();
			area += (long) (slabMaxX - slabMinX) * coveredLength(intervals);
		}
		return area;
	}

	private static long coveredLength(List<Interval> intervals) {
		if (intervals.isEmpty()) {
			return 0L;
		}
		long length = 0L;
		int currentMin = intervals.get(0).min();
		int currentMax = intervals.get(0).max();
		for (int i = 1; i < intervals.size(); i++) {
			Interval interval = intervals.get(i);
			if (interval.min() > currentMax) {
				length += currentMax - currentMin;
				currentMin = interval.min();
				currentMax = interval.max();
			} else {
				currentMax = Math.max(currentMax, interval.max());
			}
		}
		return length + currentMax - currentMin;
	}

	record Policy(double minimumAreaRatio, boolean requireCenterSupport,
			int maximumOverhangMillimeters, double maximumOverhangRatio) {

		Policy {
			if (minimumAreaRatio < 0.0 || minimumAreaRatio > 1.0) {
				throw new IllegalArgumentException("minimumAreaRatio must be between 0 and 1");
			}
			if (maximumOverhangMillimeters < 0 || maximumOverhangRatio < 0.0 || maximumOverhangRatio > 1.0) {
				throw new IllegalArgumentException("maximum overhang values are invalid");
			}
		}

		int maximumOverhang(int dimension) {
			return Math.min(maximumOverhangMillimeters, (int) Math.floor(dimension * maximumOverhangRatio));
		}
	}

	record Validation(boolean valid, String boxId, int x, int y, int z, double supportRatio, String reason) {

		static Validation supported() {
			return new Validation(true, null, 0, 0, 0, 1.0, null);
		}

		static Validation invalid(Placement placement, double supportRatio, String reason) {
			return new Validation(false, placement.getBox().getId(), placement.getAbsoluteX(),
					placement.getAbsoluteY(), placement.getAbsoluteZ(), supportRatio, reason);
		}
	}

	private record Rectangle(int minX, int minY, int maxX, int maxY) {
		boolean contains(double x, double y) {
			return x >= minX && x < maxX && y >= minY && y < maxY;
		}
	}

	private record Interval(int min, int max) {
	}
}
