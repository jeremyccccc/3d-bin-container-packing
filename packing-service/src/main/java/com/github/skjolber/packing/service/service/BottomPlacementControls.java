package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Order;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.Stack;
import com.github.skjolber.packing.api.packager.BoxItemSource;
import com.github.skjolber.packing.api.packager.control.point.PointControls;
import com.github.skjolber.packing.api.point.Point;
import com.github.skjolber.packing.api.point.PointCalculator;
import com.github.skjolber.packing.packer.plain.PlainPlacement;
import com.github.skjolber.packing.packer.plain.PlainPlacementControls;

final class BottomPlacementControls extends PlainPlacementControls {

	BottomPlacementControls(BoxItemSource boxItems, int boxItemsStartIndex, int boxItemsEndIndex,
			PointControls pointControls, PointCalculator pointCalculator, Container container, Stack stack,
			Order order, Comparator<Placement> placementComparator,
			Comparator<BoxItem> boxItemComparator, boolean requireFullSupport) {
		super(boxItems, boxItemsStartIndex, boxItemsEndIndex, pointControls, pointCalculator, container, stack,
				order, placementComparator, boxItemComparator, requireFullSupport);
	}

	@Override
	protected PlainPlacement createPlacement(Point point, BoxStackValue stackValue) {
		if (!BottomRuleSupport.isAllowed(point, stackValue, pointCalculator.getPlacements())) {
			return null;
		}
		if (!NoPressRuleSupport.isAllowed(point, stackValue, pointCalculator.getPlacements())) {
			return null;
		}
		if (!DoorSideRuleSupport.isAllowed(container, point, stackValue, pointCalculator.getPlacements())) {
			return null;
		}
		return super.createPlacement(point, stackValue);
	}
}
