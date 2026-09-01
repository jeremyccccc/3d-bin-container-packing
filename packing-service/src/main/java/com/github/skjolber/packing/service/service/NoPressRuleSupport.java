package com.github.skjolber.packing.service.service;

import java.util.List;
import java.util.Objects;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;

final class NoPressRuleSupport {

	private static final int MAX_SAME_HOUSE_LAYERS = 3;

	private NoPressRuleSupport() {
	}

	static boolean hasNoPressRule(Box box) {
		Boolean noPress = box.getProperty(PackingMapper.PROP_NO_PRESS);
		return Boolean.TRUE.equals(noPress);
	}

	static boolean isAllowed(Point point, BoxStackValue stackValue, List<Placement> placements) {
		return isAllowed(
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
				if (!isAllowed(
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

	private static boolean isAllowed(int minX, int minY, int minZ, int maxX, int maxY,
			Box candidate, List<Placement> placements) {
		if (minZ == 0) {
			return true;
		}

		String candidateHouseBsId = candidate.getProperty(PackingMapper.PROP_HOUSE_BS_ID);
		boolean candidateNoPress = hasNoPressRule(candidate);
		int sameHouseNoPressLayersBelow = 0;

		for (Placement support : placements) {
			if (support.getAbsoluteEndZ() != minZ - 1 || !overlaps(minX, minY, maxX, maxY, support)) {
				continue;
			}

			if (!hasNoPressRule(support.getBox())) {
				continue;
			}

			String supportHouseBsId = support.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			if (!Objects.equals(candidateHouseBsId, supportHouseBsId)) {
				return false;
			}
			if (!candidateNoPress) {
				return false;
			}
			sameHouseNoPressLayersBelow = Math.max(sameHouseNoPressLayersBelow, countSameHouseNoPressLayers(support, placements));
		}

		return sameHouseNoPressLayersBelow < MAX_SAME_HOUSE_LAYERS;
	}

	private static int countSameHouseNoPressLayers(Placement top, List<Placement> placements) {
		String houseBsId = top.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
		int layers = 1;
		Placement current = top;

		while (true) {
			Placement next = findSameHouseNoPressSupport(current, houseBsId, placements);
			if (next == null) {
				return layers;
			}
			layers++;
			current = next;
		}
	}

	private static Placement findSameHouseNoPressSupport(Placement placement, String houseBsId, List<Placement> placements) {
		for (Placement support : placements) {
			if (support == placement || support.getAbsoluteEndZ() != placement.getAbsoluteZ() - 1) {
				continue;
			}
			if (!hasNoPressRule(support.getBox())) {
				continue;
			}
			String supportHouseBsId = support.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			if (Objects.equals(houseBsId, supportHouseBsId) && overlaps(placement, support)) {
				return support;
			}
		}
		return null;
	}

	private static boolean overlaps(Placement upper, Placement lower) {
		return lower.getAbsoluteX() <= upper.getAbsoluteEndX()
				&& lower.getAbsoluteEndX() >= upper.getAbsoluteX()
				&& lower.getAbsoluteY() <= upper.getAbsoluteEndY()
				&& lower.getAbsoluteEndY() >= upper.getAbsoluteY();
	}

	private static boolean overlaps(int minX, int minY, int maxX, int maxY, Placement support) {
		return support.getAbsoluteX() <= maxX
				&& support.getAbsoluteEndX() >= minX
				&& support.getAbsoluteY() <= maxY
				&& support.getAbsoluteEndY() >= minY;
	}
}
