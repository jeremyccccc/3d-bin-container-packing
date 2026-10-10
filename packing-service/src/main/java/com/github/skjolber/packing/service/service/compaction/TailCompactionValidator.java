package com.github.skjolber.packing.service.service.compaction;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.service.service.mapping.PackingMapper;
import com.github.skjolber.packing.service.service.rule.PlacementGeometry;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TailCompactionValidator {

	private final PlacementSupport.Policy supportPolicy;

	TailCompactionValidator(PlacementSupport.Policy supportPolicy) {
		this.supportPolicy = supportPolicy;
	}

	boolean validGeometry(PackagerResult result, String phase, String houseBsId, Container target) {
		if (result == null || !result.isSuccess() || result.size() != 1) return false;
		PlacementGeometry.Validation geometry = PlacementGeometry.validate(result.get(0));
		if (geometry.valid()) return true;
		System.err.println("packing-service tail-compaction rejected phase=" + phase
				+ " houseBsId=" + houseBsId + " target=" + target.getId()
				+ " reason=" + geometry.message());
		return false;
	}

	boolean supported(Container container) {
		List<Placement> placements = container.getStack().getPlacements();
		for (Placement placement : placements) {
			if (!PlacementSupport.validate(placement, placements, supportPolicy).valid()) return false;
		}
		return true;
	}

	boolean validFinalResult(List<Container> source, List<Container> compacted) {
		if (!boxCounts(source).equals(boxCounts(compacted))) return false;
		Map<String, Integer> houseBillContainers = new LinkedHashMap<>();
		for (int containerIndex = 0; containerIndex < compacted.size(); containerIndex++) {
			Container container = compacted.get(containerIndex);
			List<Placement> placements = container.getStack().getPlacements();
			for (int i = 0; i < placements.size(); i++) {
				Placement placement = placements.get(i);
				if (placement.getAbsoluteX() < 0 || placement.getAbsoluteY() < 0 || placement.getAbsoluteZ() < 0
						|| placement.getAbsoluteEndX() >= container.getLoadDx()
						|| placement.getAbsoluteEndY() >= container.getLoadDy()
						|| placement.getAbsoluteEndZ() >= container.getLoadDz()) return false;
				for (int j = 0; j < i; j++) if (intersects(placement, placements.get(j))) return false;
				if (!PlacementSupport.validate(placement, placements, supportPolicy).valid()) return false;
				String houseBsId = String.valueOf((Object) placement.getBox()
						.getProperty(PackingMapper.PROP_HOUSE_BS_ID));
				Integer previous = houseBillContainers.putIfAbsent(houseBsId, containerIndex);
				if (previous != null && previous != containerIndex) return false;
			}
		}
		return true;
	}

	private static Map<String, Integer> boxCounts(List<Container> containers) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Container container : containers) {
			for (Placement placement : container.getStack().getPlacements()) {
				counts.merge(placement.getBox().getId(), 1, Integer::sum);
			}
		}
		return counts;
	}

	private static boolean intersects(Placement a, Placement b) {
		return a.getAbsoluteX() <= b.getAbsoluteEndX() && b.getAbsoluteX() <= a.getAbsoluteEndX()
				&& a.getAbsoluteY() <= b.getAbsoluteEndY() && b.getAbsoluteY() <= a.getAbsoluteEndY()
				&& a.getAbsoluteZ() <= b.getAbsoluteEndZ() && b.getAbsoluteZ() <= a.getAbsoluteEndZ();
	}
}
