package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

final class PlacementSupport {

	private PlacementSupport() {
	}

	static boolean isValid(PackagerResult result) {
		return result.getContainers().stream().allMatch(container -> {
			List<Placement> placements = container.getStack().getPlacements();
			return placements.stream().allMatch(placement -> isFullySupported(placement, placements));
		});
	}

	static boolean isFullySupported(Placement candidate, List<Placement> placements) {
		if (candidate.getAbsoluteZ() == 0) {
			return true;
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
		return coveredArea(supports) == requiredArea;
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

	private record Rectangle(int minX, int minY, int maxX, int maxY) {
	}

	private record Interval(int min, int max) {
	}
}
