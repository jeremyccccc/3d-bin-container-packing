package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.comparator.VolumeThenWeightBoxItemComparator;

final class RuleBoxItemComparator implements Comparator<BoxItem> {

	private final VolumeThenWeightBoxItemComparator delegate = VolumeThenWeightBoxItemComparator.getInstance();

	@Override
	public int compare(BoxItem reference, BoxItem potentiallyBetter) {
		boolean referenceDoorSide = DoorSideRuleSupport.hasDoorSideRule(reference.getBox());
		boolean potentiallyBetterDoorSide = DoorSideRuleSupport.hasDoorSideRule(potentiallyBetter.getBox());
		if (referenceDoorSide != potentiallyBetterDoorSide) {
			return potentiallyBetterDoorSide ? -1 : 1;
		}
		return delegate.compare(reference, potentiallyBetter);
	}
}
