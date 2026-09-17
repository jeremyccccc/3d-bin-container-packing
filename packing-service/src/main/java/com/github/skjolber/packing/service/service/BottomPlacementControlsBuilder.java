package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.packer.plain.PlainPlacementControlsBuilder;

final class BottomPlacementControlsBuilder extends PlainPlacementControlsBuilder {

	BottomPlacementControlsBuilder(Comparator<Placement> placementComparator,
			Comparator<BoxItem> boxItemComparator, PlacementSupport.Policy supportPolicy) {
		this(placementComparator, boxItemComparator, supportPolicy, false, false);
	}

	BottomPlacementControlsBuilder(Comparator<Placement> placementComparator,
			Comparator<BoxItem> boxItemComparator, PlacementSupport.Policy supportPolicy,
			boolean preferDoorSidePlacements) {
		this(placementComparator, boxItemComparator, supportPolicy, preferDoorSidePlacements, false);
	}

	BottomPlacementControlsBuilder(Comparator<Placement> placementComparator,
			Comparator<BoxItem> boxItemComparator, PlacementSupport.Policy supportPolicy,
			boolean preferDoorSidePlacements, boolean layoutScoring) {
		super(placementComparator, boxItemComparator, false);
		this.supportPolicy = supportPolicy;
		this.preferDoorSidePlacements = preferDoorSidePlacements;
		this.layoutScoring = layoutScoring;
	}

	private final PlacementSupport.Policy supportPolicy;
	private final boolean preferDoorSidePlacements;
	private final boolean layoutScoring;

	@Override
	public BottomPlacementControls build() {
		return new BottomPlacementControls(boxItems, boxItemsStartIndex, boxItemsEndIndex,
				pointControls, pointCalculator, container, stack, order,
				preferDoorSidePlacements ? new RulePlacementComparator(container) : plainPlacementComparator,
				boxItemComparator, supportPolicy, layoutScoring);
	}
}
