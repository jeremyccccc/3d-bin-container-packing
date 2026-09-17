package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.packer.plain.PlainPlacement;

/**
 * Prefers safe, low placements first, then layouts which keep equal cargo in
 * compact rows and avoid small unusable strips.
 */
final class LayoutPlacementComparator implements Comparator<Placement> {

	@Override
	public int compare(Placement reference, Placement candidate) {
		int result = Long.compare(supportPermille(reference), supportPermille(candidate));
		if (result != 0) {
			return result;
		}

		// A lower Z is better. The placement-control comparator expects a positive
		// value when the reference is better than the candidate.
		result = Integer.compare(candidate.getAbsoluteZ(), reference.getAbsoluteZ());
		if (result != 0) {
			return result;
		}

		if (reference instanceof LayoutPlacement referenceLayout
				&& candidate instanceof LayoutPlacement candidateLayout) {
			result = referenceLayout.getLayoutScore().compareTo(candidateLayout.getLayoutScore());
			if (result != 0) {
				return result;
			}
		}

		return Long.compare(reference.getStackValue().getArea(), candidate.getStackValue().getArea());
	}

	private static long supportPermille(Placement placement) {
		if (placement instanceof PlainPlacement plain) {
			return plain.getSupportArea() * 1000L / placement.getStackValue().getArea();
		}
		return 0L;
	}
}
