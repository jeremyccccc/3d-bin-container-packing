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
			Comparator<BoxItem> boxItemComparator, PlacementSupport.Policy supportPolicy,
			boolean layoutScoring) {
		super(boxItems, boxItemsStartIndex, boxItemsEndIndex, pointControls, pointCalculator, container, stack,
				order, placementComparator, boxItemComparator, false);
		this.supportPolicy = supportPolicy;
		this.layoutScoring = layoutScoring;
	}

	private final PlacementSupport.Policy supportPolicy;
	private final boolean layoutScoring;

	@Override
	protected PlainPlacement createPlacement(Point point, BoxStackValue stackValue) {
		PackingSearchDiagnostics.candidate();
		if (!BottomRuleSupport.isAllowed(point, stackValue, pointCalculator.getPlacements())) {
			PackingSearchDiagnostics.rejected("bottom-rule");
			return null;
		}
		if (!NoPressRuleSupport.isAllowed(point, stackValue, pointCalculator.getPlacements())) {
			PackingSearchDiagnostics.rejected("no-press");
			return null;
		}
		if (!DoorSideRuleSupport.isAllowed(container, point, stackValue, pointCalculator.getPlacements())) {
			PackingSearchDiagnostics.rejected("door-side");
			return null;
		}
		PlacementSupport.Validation support = PlacementSupport.validate(
				point, stackValue, pointCalculator.getPlacements(), supportPolicy);
		if (!support.valid()) {
			PackingSearchDiagnostics.rejected(support.reason());
			return null;
		}
		if (layoutScoring) {
			long supportedArea = PlainPlacementControls.calculateAreaSupport(pointCalculator, point, stackValue);
			return new LayoutPlacement(stackValue, point, supportedArea,
					LayoutScoring.evaluate(point, stackValue, pointCalculator.getPlacements(), container,
							minimumRemainingFootprintEdge()));
		}
		return super.createPlacement(point, stackValue);
	}

	private int minimumRemainingFootprintEdge() {
		int minimum = Integer.MAX_VALUE;
		for (BoxItem item : boxItems) {
			for (BoxStackValue value : item.getBox().getStackValues()) {
				minimum = Math.min(minimum, Math.min(value.getDx(), value.getDy()));
			}
		}
		return minimum == Integer.MAX_VALUE ? 1 : minimum;
	}
}
