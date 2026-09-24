package com.github.skjolber.packing.service.service;

import java.util.List;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Placement;

/** Strict container-boundary and placement-overlap validation. */
final class PlacementGeometry {

	private PlacementGeometry() {
	}

	static Validation validate(Container container) {
		return validate(container, container.getStack().getPlacements());
	}

	static Validation validate(Container container, List<Placement> placements) {
		for (int i = 0; i < placements.size(); i++) {
			Placement placement = placements.get(i);
			if (placement.getAbsoluteX() < 0 || placement.getAbsoluteY() < 0 || placement.getAbsoluteZ() < 0
					|| placement.getAbsoluteEndX() >= container.getLoadDx()
					|| placement.getAbsoluteEndY() >= container.getLoadDy()
					|| placement.getAbsoluteEndZ() >= container.getLoadDz()) {
				return Validation.invalid("Placement outside container at index " + i + ": " + describe(placement));
			}
			for (int j = 0; j < i; j++) {
				Placement other = placements.get(j);
				if (intersects(placement, other)) {
					return Validation.invalid("Placements overlap at indexes " + j + " and " + i
							+ ": first=" + describe(other) + ", second=" + describe(placement));
				}
			}
		}
		return Validation.success();
	}

	static boolean intersects(Placement a, Placement b) {
		return a.getAbsoluteX() <= b.getAbsoluteEndX() && b.getAbsoluteX() <= a.getAbsoluteEndX()
				&& a.getAbsoluteY() <= b.getAbsoluteEndY() && b.getAbsoluteY() <= a.getAbsoluteEndY()
				&& a.getAbsoluteZ() <= b.getAbsoluteEndZ() && b.getAbsoluteZ() <= a.getAbsoluteEndZ();
	}

	private static String describe(Placement placement) {
		Object houseBsId = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
		Object inboundId = placement.getBox().getProperty(PackingMapper.PROP_INBOUND_ID);
		return "boxId=" + placement.getBox().getId()
				+ ", houseBsId=" + houseBsId
				+ ", inboundId=" + inboundId
				+ ", xyz=" + placement.getAbsoluteX() + ',' + placement.getAbsoluteY() + ',' + placement.getAbsoluteZ()
				+ ", end=" + placement.getAbsoluteEndX() + ',' + placement.getAbsoluteEndY() + ','
				+ placement.getAbsoluteEndZ();
	}

	record Validation(boolean valid, String message) {
		static Validation success() {
			return new Validation(true, "valid");
		}

		static Validation invalid(String message) {
			return new Validation(false, message);
		}
	}
}
