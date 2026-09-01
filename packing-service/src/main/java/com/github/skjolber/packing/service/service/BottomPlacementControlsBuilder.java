package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.packer.plain.PlainPlacementControlsBuilder;

final class BottomPlacementControlsBuilder extends PlainPlacementControlsBuilder {

	BottomPlacementControlsBuilder(Comparator<Placement> placementComparator,
			Comparator<BoxItem> boxItemComparator, boolean requireFullSupport) {
		this(placementComparator, boxItemComparator, requireFullSupport, false);
	}

	BottomPlacementControlsBuilder(Comparator<Placement> placementComparator,
			Comparator<BoxItem> boxItemComparator, boolean requireFullSupport, boolean preferDoorSidePlacements) {
		super(placementComparator, boxItemComparator, requireFullSupport);
		this.preferDoorSidePlacements = preferDoorSidePlacements;
	}

	private final boolean preferDoorSidePlacements;

	@Override
	public BottomPlacementControls build() {
		return new BottomPlacementControls(boxItems, boxItemsStartIndex, boxItemsEndIndex,
				pointControls, pointCalculator, container, stack, order,
				preferDoorSidePlacements ? new RulePlacementComparator(container) : plainPlacementComparator,
				boxItemComparator, requireFullSupport);
	}
}
