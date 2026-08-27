package com.github.skjolber.packing.service.service;

import java.util.List;
import java.util.Objects;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;

final class BottomRuleSupport {

	private static final int BOTTOM = 1;

	private BottomRuleSupport() {
	}

	static boolean hasBottomRule(Box box) {
		Integer heightPosition = box.getProperty(PackingMapper.PROP_HEIGHT_POSITION);
		return heightPosition != null && heightPosition == BOTTOM;
	}

	static boolean isAllowed(Point point, BoxStackValue stackValue, List<Placement> placements) {
		if (!hasBottomRule(stackValue.getBox()) || point.getMinZ() == 0) {
			return true;
		}
		return hasOnlySameHouseSupport(
				point.getMinX(),
				point.getMinY(),
				point.getMinZ(),
				point.getMinX() + stackValue.getDx() - 1,
				point.getMinY() + stackValue.getDy() - 1,
				stackValue.getBox(),
				placements);
	}

	static boolean isValid(PackagerResult result) {
		return result.getContainers().stream().allMatch(container -> {
			List<Placement> placements = container.getStack().getPlacements();
			for (Placement placement : placements) {
				if (!hasBottomRule(placement.getBox()) || placement.getAbsoluteZ() == 0) {
					continue;
				}
				if (!hasOnlySameHouseSupport(
						placement.getAbsoluteX(),
						placement.getAbsoluteY(),
						placement.getAbsoluteZ(),
						placement.getAbsoluteEndX(),
						placement.getAbsoluteEndY(),
						placement.getBox(),
						placements)) {
					return false;
				}
			}
			return true;
		});
	}

	private static boolean hasOnlySameHouseSupport(int minX, int minY, int minZ, int maxX, int maxY,
			Box candidate, List<Placement> placements) {
		String houseBsId = candidate.getProperty(PackingMapper.PROP_HOUSE_BS_ID);
		boolean hasSupport = false;
		for (Placement support : placements) {
			if (support.getAbsoluteEndZ() != minZ - 1 || !overlaps(minX, minY, maxX, maxY, support)) {
				continue;
			}
			hasSupport = true;
			String supportHouseBsId = support.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			if (!Objects.equals(houseBsId, supportHouseBsId)) {
				return false;
			}
		}
		return hasSupport;
	}

	private static boolean overlaps(int minX, int minY, int maxX, int maxY, Placement support) {
		return support.getAbsoluteX() <= maxX
				&& support.getAbsoluteEndX() >= minX
				&& support.getAbsoluteY() <= maxY
				&& support.getAbsoluteEndY() >= minY;
	}
}
