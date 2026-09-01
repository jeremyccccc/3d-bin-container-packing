package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.packer.plain.PlainPlacementComparator;

final class RulePlacementComparator implements Comparator<Placement> {

	private final PlainPlacementComparator delegate = new PlainPlacementComparator();
	private final Container container;

	RulePlacementComparator(Container container) {
		this.container = container;
	}

	@Override
	public int compare(Placement reference, Placement potentiallyBetter) {
		boolean referenceDoorSide = DoorSideRuleSupport.hasDoorSideRule(reference.getBox());
		boolean potentiallyBetterDoorSide = DoorSideRuleSupport.hasDoorSideRule(potentiallyBetter.getBox());
		if (referenceDoorSide != potentiallyBetterDoorSide) {
			return potentiallyBetterDoorSide ? -1 : 1;
		}
		if (!referenceDoorSide) {
			return delegate.compare(reference, potentiallyBetter);
		}

		int result = Integer.compare(axisStart(potentiallyBetter), axisStart(reference));
		if (result != 0) {
			return result;
		}
		result = Integer.compare(doorAxisSize(potentiallyBetter), doorAxisSize(reference));
		if (result != 0) {
			return result;
		}
		return delegate.compare(reference, potentiallyBetter);
	}

	private int axisStart(Placement placement) {
		if (container.getLoadDx() >= container.getLoadDy()) {
			return placement.getAbsoluteX();
		}
		return placement.getAbsoluteY();
	}

	private int doorAxisSize(Placement placement) {
		if (container.getLoadDx() >= container.getLoadDy()) {
			return placement.getStackValue().getDx();
		}
		return placement.getStackValue().getDy();
	}
}
