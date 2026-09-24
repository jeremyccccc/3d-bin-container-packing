package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

/**
 * Destroys a tail region of a high-volume partial packing and repairs that
 * region while the unaffected placements remain fixed obstacles.
 */
final class BlockBeamLargeNeighborhoodSearch {

	private static final double[] TAIL_START_RATIOS = {0.82, 0.72, 0.62};

	private final PlacementSupport.Policy supportPolicy;

	BlockBeamLargeNeighborhoodSearch(PlacementSupport.Policy supportPolicy) {
		this.supportPolicy = supportPolicy;
	}

	PackagerResult repair(BlockBeamSearchPackager.PartialSolution source,
			long deadlineMillis, String logContext) {
		if (source == null || source.remainingItems().isEmpty()) return null;
		List<Placement> sourcePlacements = source.container().getStack().getPlacements();
		if (sourcePlacements.isEmpty()) return null;
		Map<String, Integer> expectedCounts = counts(sourcePlacements, source.remainingItems());
		int occupiedEndX = sourcePlacements.stream()
				.mapToInt(placement -> placement.getAbsoluteEndX() + 1).max().orElse(0);

		for (int attempt = 0; attempt < TAIL_START_RATIOS.length; attempt++) {
			if (expired(deadlineMillis)) break;
			int threshold = (int) Math.round(occupiedEndX * TAIL_START_RATIOS[attempt]);
			DestroyedNeighborhood neighborhood = destroy(source, threshold);
			if (neighborhood.items().isEmpty()) continue;
			long remainingMillis = deadlineMillis <= 0L ? 0L
					: Math.max(1L, deadlineMillis - System.currentTimeMillis());
			int remainingAttempts = TAIL_START_RATIOS.length - attempt;
			long attemptDeadline = deadlineMillis <= 0L ? 0L
					: Math.min(deadlineMillis,
							System.currentTimeMillis() + Math.max(1L, remainingMillis / remainingAttempts));
			BlockBeamSearchPackager.SearchProfile profile = attempt == 0
					? BlockBeamSearchPackager.SearchProfile.VOLUME_FIRST
					: BlockBeamSearchPackager.SearchProfile.BALANCED;
			BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
					96, 64, supportPolicy, profile);
			PackagerResult result = packager.pack(source.container(), neighborhood.items(),
					neighborhood.fixedPlacements(), attemptDeadline,
					logContext + " lnsAttempt=" + (attempt + 1)
							+ " threshold=" + threshold + " profile=" + profile.propertyValue());
			if (valid(result, expectedCounts)) {
				System.out.println("packing-service BLOCK-BEAM-LNS" + logContext
						+ " success=true attempt=" + (attempt + 1)
						+ " threshold=" + threshold
						+ " fixed=" + neighborhood.fixedPlacements().size()
						+ " repackedUnits=" + unitCount(neighborhood.items()));
				return result;
			}
		}
		System.out.println("packing-service BLOCK-BEAM-LNS" + logContext + " success=false");
		return null;
	}

	DestroyedNeighborhood destroy(BlockBeamSearchPackager.PartialSolution source, int threshold) {
		List<Placement> fixed = new ArrayList<>();
		List<Placement> removed = new ArrayList<>();
		for (Placement placement : source.container().getStack().getPlacements()) {
			if (placement.getAbsoluteEndX() + 1 <= threshold) fixed.add(placement);
			else removed.add(placement);
		}
		removeUnsupportedDependants(fixed, removed);

		Map<String, BoxCount> items = new LinkedHashMap<>();
		for (BoxItem item : source.remainingItems()) {
			items.computeIfAbsent(item.getBox().getId(), ignored -> new BoxCount(item.getBox()))
					.count += item.getCount();
		}
		for (Placement placement : removed) {
			Box box = placement.getBox();
			items.computeIfAbsent(box.getId(), ignored -> new BoxCount(box)).count++;
		}
		List<BoxItem> toPack = items.values().stream()
				.map(value -> new BoxItem(value.box, value.count)).toList();
		return new DestroyedNeighborhood(List.copyOf(fixed), toPack);
	}

	private void removeUnsupportedDependants(List<Placement> fixed, List<Placement> removed) {
		boolean changed;
		do {
			changed = false;
			for (int index = fixed.size() - 1; index >= 0; index--) {
				Placement placement = fixed.get(index);
				if (placement.getAbsoluteZ() == 0) continue;
				if (PlacementSupport.validate(placement, fixed, supportPolicy).valid()) continue;
				removed.add(placement);
				fixed.remove(index);
				changed = true;
			}
		} while (changed);
	}

	private boolean valid(PackagerResult result, Map<String, Integer> expectedCounts) {
		if (result == null || !result.isSuccess() || result.size() != 1) return false;
		if (!expectedCounts.equals(counts(result.get(0).getStack().getPlacements(), List.of()))) return false;
		return PlacementSupport.validate(result, supportPolicy).valid()
				&& BottomRuleSupport.isValid(result) && NoPressRuleSupport.isValid(result);
	}

	private static Map<String, Integer> counts(List<Placement> placements, List<BoxItem> remaining) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Placement placement : placements) counts.merge(placement.getBox().getId(), 1, Integer::sum);
		for (BoxItem item : remaining) counts.merge(item.getBox().getId(), item.getCount(), Integer::sum);
		return counts;
	}

	private static int unitCount(List<BoxItem> items) {
		return items.stream().mapToInt(BoxItem::getCount).sum();
	}

	private static boolean expired(long deadlineMillis) {
		return deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	record DestroyedNeighborhood(List<Placement> fixedPlacements, List<BoxItem> items) {
	}

	private static final class BoxCount {
		private final Box box;
		private int count;

		private BoxCount(Box box) {
			this.box = box;
		}
	}
}
