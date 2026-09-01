package com.github.skjolber.packing.service.service;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;

final class DoorSideRuleSupport {

	private DoorSideRuleSupport() {
	}

	static boolean hasDoorSideRule(Box box) {
		Boolean doorSide = box.getProperty(PackingMapper.PROP_DOOR_SIDE);
		return Boolean.TRUE.equals(doorSide);
	}

	static long candidateDistance(Container container, Point point, BoxStackValue stackValue) {
		if (isDoorOnXMax(container)) {
			return container.getLoadDx() - 1L - point.getMinX();
		}
		return container.getLoadDy() - 1L - point.getMinY();
	}

	static long candidateDistance(Container container, Placement placement) {
		if (isDoorOnXMax(container)) {
			return container.getLoadDx() - 1L - placement.getAbsoluteX();
		}
		return container.getLoadDy() - 1L - placement.getAbsoluteY();
	}

	static boolean isAllowed(Container container, Point point, BoxStackValue stackValue, List<Placement> placements) {
		for (Placement placement : placements) {
			if (hasDoorSideRule(stackValue.getBox())) {
				if (!sameHouseBill(stackValue.getBox(), placement.getBox()) && blocksAfterDoorSideMirror(container, placement, point, stackValue)) {
					return false;
				}
			} else if (hasDoorSideRule(placement.getBox()) && blocksAfterDoorSideMirror(container, point, stackValue, placement)) {
				return false;
			}
		}

		return true;
	}

	static boolean isValid(PackagerResult result) {
		for (Container container : result.getContainers()) {
			Set<String> doorSideHouseBills = new HashSet<>();
			for (Placement placement : container.getStack().getPlacements()) {
				if (!hasDoorSideRule(placement.getBox())) {
					continue;
				}
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				doorSideHouseBills.add(houseBsId);
			}

			for (String doorSideHouse : doorSideHouseBills) {
				for (Placement placement : container.getStack().getPlacements()) {
					String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
					if (doorSideHouse.equals(houseBsId)) {
						continue;
					}
					if (blocksDoorSideAccess(container, placement, doorSideHouse)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	static Score score(PackagerResult result) {
		long totalDistance = 0;
		long totalSpread = 0;
		int houseBillCount = 0;

		for (Container container : result.getContainers()) {
			Map<String, Bounds> boundsByHouseBill = new HashMap<>();
			for (Placement placement : container.getStack().getPlacements()) {
				if (!hasDoorSideRule(placement.getBox())) {
					continue;
				}
				String houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
				Bounds bounds = boundsByHouseBill.computeIfAbsent(houseBsId, ignored -> new Bounds());
				if (isDoorOnXMax(container)) {
					bounds.accept(placement.getAbsoluteX(), placement.getAbsoluteEndX());
				} else {
					bounds.accept(placement.getAbsoluteY(), placement.getAbsoluteEndY());
				}
			}

			for (Bounds bounds : boundsByHouseBill.values()) {
				houseBillCount++;
				if (isDoorOnXMax(container)) {
					totalDistance += container.getLoadDx() - 1L - bounds.minStart;
				} else {
					totalDistance += container.getLoadDy() - 1L - bounds.minStart;
				}
				totalSpread += bounds.maxEnd - bounds.minStart;
			}
		}

		return new Score(totalDistance, totalSpread, houseBillCount);
	}

	static void mirrorToDoorSide(PackagerResult result) {
		mirror(result);
	}

	static boolean hasDoorSideRule(List<com.github.skjolber.packing.api.BoxItem> items) {
		return items.stream().anyMatch(item -> hasDoorSideRule(item.getBox()));
	}

	private static void mirror(PackagerResult result) {
		for (Container container : result.getContainers()) {
			for (Placement placement : container.getStack().getPlacements()) {
				if (isDoorOnXMax(container)) {
					placement.setPoint(
							placement.getPointIndex(),
							container.getLoadDx() - 1 - placement.getAbsoluteEndX(),
							placement.getAbsoluteY(),
							placement.getAbsoluteZ());
				} else {
					placement.setPoint(
							placement.getPointIndex(),
							placement.getAbsoluteX(),
							container.getLoadDy() - 1 - placement.getAbsoluteEndY(),
							placement.getAbsoluteZ());
				}
			}
		}
	}

	private static boolean isDoorOnXMax(Container container) {
		return container.getLoadDx() >= container.getLoadDy();
	}

	private static boolean blocksDoorSideAccess(Container container, Placement other, String doorSideHouse) {
		for (Placement doorSide : container.getStack().getPlacements()) {
			if (!doorSideHouse.equals(doorSide.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID))) {
				continue;
			}
			if (axisEnd(container, other) > axisEnd(container, doorSide) && crossSectionOverlaps(container, other, doorSide)) {
				return true;
			}
		}
		return false;
	}

	private static boolean blocksAfterDoorSideMirror(Container container, Placement other, Point doorSidePoint, BoxStackValue doorSideStackValue) {
		return axisStart(container, other) < axisStart(container, doorSidePoint)
				&& crossSectionOverlaps(container, other, doorSidePoint, doorSideStackValue);
	}

	private static boolean blocksAfterDoorSideMirror(Container container, Point otherPoint, BoxStackValue otherStackValue, Placement doorSide) {
		return axisStart(container, otherPoint) < axisStart(container, doorSide)
				&& crossSectionOverlaps(container, otherPoint, otherStackValue, doorSide);
	}

	private static boolean sameHouseBill(Box first, Box second) {
		String firstHouseBsId = first.getProperty(PackingMapper.PROP_HOUSE_BS_ID);
		String secondHouseBsId = second.getProperty(PackingMapper.PROP_HOUSE_BS_ID);
		return firstHouseBsId != null && firstHouseBsId.equals(secondHouseBsId);
	}

	private static boolean crossSectionOverlaps(Container container, Placement first, Placement second) {
		if (isDoorOnXMax(container)) {
			return overlaps(first.getAbsoluteY(), first.getAbsoluteEndY(), second.getAbsoluteY(), second.getAbsoluteEndY())
					&& overlaps(first.getAbsoluteZ(), first.getAbsoluteEndZ(), second.getAbsoluteZ(), second.getAbsoluteEndZ());
		}
		return overlaps(first.getAbsoluteX(), first.getAbsoluteEndX(), second.getAbsoluteX(), second.getAbsoluteEndX())
				&& overlaps(first.getAbsoluteZ(), first.getAbsoluteEndZ(), second.getAbsoluteZ(), second.getAbsoluteEndZ());
	}

	private static boolean crossSectionOverlaps(Container container, Placement first, Point secondPoint, BoxStackValue secondStackValue) {
		if (isDoorOnXMax(container)) {
			return overlaps(first.getAbsoluteY(), first.getAbsoluteEndY(), secondPoint.getMinY(), secondPoint.getMinY() + secondStackValue.getDy() - 1)
					&& overlaps(first.getAbsoluteZ(), first.getAbsoluteEndZ(), secondPoint.getMinZ(), secondPoint.getMinZ() + secondStackValue.getDz() - 1);
		}
		return overlaps(first.getAbsoluteX(), first.getAbsoluteEndX(), secondPoint.getMinX(), secondPoint.getMinX() + secondStackValue.getDx() - 1)
				&& overlaps(first.getAbsoluteZ(), first.getAbsoluteEndZ(), secondPoint.getMinZ(), secondPoint.getMinZ() + secondStackValue.getDz() - 1);
	}

	private static boolean crossSectionOverlaps(Container container, Point firstPoint, BoxStackValue firstStackValue, Placement second) {
		if (isDoorOnXMax(container)) {
			return overlaps(firstPoint.getMinY(), firstPoint.getMinY() + firstStackValue.getDy() - 1, second.getAbsoluteY(), second.getAbsoluteEndY())
					&& overlaps(firstPoint.getMinZ(), firstPoint.getMinZ() + firstStackValue.getDz() - 1, second.getAbsoluteZ(), second.getAbsoluteEndZ());
		}
		return overlaps(firstPoint.getMinX(), firstPoint.getMinX() + firstStackValue.getDx() - 1, second.getAbsoluteX(), second.getAbsoluteEndX())
				&& overlaps(firstPoint.getMinZ(), firstPoint.getMinZ() + firstStackValue.getDz() - 1, second.getAbsoluteZ(), second.getAbsoluteEndZ());
	}

	private static boolean overlaps(int firstStart, int firstEnd, int secondStart, int secondEnd) {
		return firstStart <= secondEnd && secondStart <= firstEnd;
	}

	private static int axisStart(Container container, Point point) {
		return isDoorOnXMax(container) ? point.getMinX() : point.getMinY();
	}

	private static int axisStart(Container container, Placement placement) {
		return isDoorOnXMax(container) ? placement.getAbsoluteX() : placement.getAbsoluteY();
	}

	private static int axisEnd(Container container, Placement placement) {
		return isDoorOnXMax(container) ? placement.getAbsoluteEndX() : placement.getAbsoluteEndY();
	}

	record Score(long totalDistance, long totalSpread, int houseBillCount) {

		static final Score NONE = new Score(0, 0, 0);

		boolean isBetterThan(Score other) {
			if (houseBillCount == 0 && other.houseBillCount == 0) {
				return false;
			}
			if (houseBillCount > 0 && other.houseBillCount == 0) {
				return true;
			}
			if (houseBillCount == 0) {
				return false;
			}
			int result = Long.compare(totalDistance, other.totalDistance);
			if (result != 0) {
				return result < 0;
			}
			return Long.compare(totalSpread, other.totalSpread) < 0;
		}
	}

	private static final class Bounds {

		private int minStart = Integer.MAX_VALUE;
		private int maxEnd = Integer.MIN_VALUE;

		void accept(int start, int end) {
			minStart = Math.min(minStart, start);
			maxEnd = Math.max(maxEnd, end);
		}
	}
}
