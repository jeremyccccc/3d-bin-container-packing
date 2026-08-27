package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.packer.plain.PlainPlacementControlsBuilder;

final class BottomPlacementControlsBuilder extends PlainPlacementControlsBuilder {

	BottomPlacementControlsBuilder(Comparator<Placement> placementComparator,
			Comparator<BoxItem> boxItemComparator, boolean requireFullSupport) {
		super(placementComparator, boxItemComparator, requireFullSupport);
	}

	@Override
	public BottomPlacementControls build() {
		return new BottomPlacementControls(boxItems, boxItemsStartIndex, boxItemsEndIndex,
				pointControls, pointCalculator, container, stack, order,
				plainPlacementComparator, boxItemComparator, requireFullSupport);
	}
}
