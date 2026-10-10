package com.github.skjolber.packing.service.service.compaction;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.service.service.blockbeam.BlockBeamSearchPackager;
import com.github.skjolber.packing.service.service.compaction.SpaceGuidedRepairPlanner.Candidate;
import com.github.skjolber.packing.service.service.compaction.SpaceGuidedRepairPlanner.Neighborhood;
import com.github.skjolber.packing.service.service.compaction.SpaceGuidedRepairPlanner.RequiredShape;
import com.github.skjolber.packing.service.service.rule.BottomRuleSupport;
import com.github.skjolber.packing.service.service.rule.NoPressRuleSupport;
import com.github.skjolber.packing.service.service.rule.PlacementGeometry;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repairs a target container around a space which is almost large enough for a
 * complete house bill. Only placements blocking that space, and placements
 * which would lose their support, are released for repacking.
 */
final class SpaceGuidedTailRepair {

	private static final int MAX_CANDIDATES = 12;
	private static final long PATTERN_SEARCH_MILLIS = 1_500L;

	private final PlacementSupport.Policy supportPolicy;
	private final int beamWidth;
	private final int branching;
	private final SpaceGuidedRepairPlanner planner;

	SpaceGuidedTailRepair(PlacementSupport.Policy supportPolicy, int beamWidth, int branching) {
		this.supportPolicy = supportPolicy;
		this.beamWidth = beamWidth;
		this.branching = branching;
		this.planner = new SpaceGuidedRepairPlanner(supportPolicy, beamWidth, branching);
	}

	PackagerResult repair(Container target, List<BoxItem> movingItems,
			long deadlineMillis, String logContext) {
		if (movingItems.isEmpty() || target.getStack().getPlacements().isEmpty()
				|| expired(deadlineMillis)) return null;
		long started = System.nanoTime();
		long patternDeadline = boundedDeadline(deadlineMillis, PATTERN_SEARCH_MILLIS);
		List<RequiredShape> patterns = planner.compactPatterns(
				target, movingItems, patternDeadline, logContext);
		if (patterns.isEmpty() || expired(deadlineMillis)) return null;

		List<Candidate> candidates = planner.candidates(target, patterns);
		int attempted = 0;
		for (int index = 0; index < candidates.size() && index < MAX_CANDIDATES; index++) {
			if (expired(deadlineMillis)) break;
			Candidate candidate = candidates.get(index);
			Neighborhood neighborhood = planner.neighborhood(target, candidate.region());
			if (neighborhood.removed().isEmpty()) continue;
			attempted++;
			List<BoxItem> toPack = merge(movingItems, neighborhood.removed());
			long attemptDeadline = sharedDeadline(deadlineMillis,
					Math.min(MAX_CANDIDATES, candidates.size()) - index);
			BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
					beamWidth, branching, supportPolicy,
					BlockBeamSearchPackager.SearchProfile.BALANCED);
			PackagerResult result = packager.pack(target, toPack, neighborhood.fixed(), attemptDeadline,
					logContext + " phase=space-guided-repair candidate=" + (index + 1)
							+ " pattern=" + candidate.pattern().key()
							+ " blockers=" + candidate.directBlockers()
							+ " removed=" + neighborhood.removed().size());
			if (!valid(result, target, movingItems, neighborhood.removed())) continue;
			long elapsedMillis = elapsedMillis(started);
			System.out.println("packing-service space-guided-tail-repair" + logContext
					+ " success=true attempted=" + attempted
					+ " candidates=" + candidates.size()
					+ " blockers=" + candidate.directBlockers()
					+ " supportClosure="
					+ (neighborhood.removed().size() - candidate.directBlockers())
					+ " repackedUnits=" + unitCount(toPack)
					+ " elapsedMs=" + elapsedMillis);
			return result;
		}
		System.out.println("packing-service space-guided-tail-repair" + logContext
				+ " success=false patterns=" + patterns.size()
				+ " candidates=" + candidates.size() + " attempted=" + attempted
				+ " elapsedMs=" + elapsedMillis(started));
		return null;
	}

	private boolean valid(PackagerResult result, Container original, List<BoxItem> moving,
			List<Placement> removed) {
		if (result == null || !result.isSuccess() || result.size() != 1) return false;
		PlacementGeometry.Validation geometry = PlacementGeometry.validate(result.get(0));
		if (!geometry.valid()) {
			System.err.println("packing-service space-guided-tail-repair rejected invalid geometry: "
					+ geometry.message());
			return false;
		}
		Map<String, Integer> expected = counts(original.getStack().getPlacements(), moving);
		Map<String, Integer> actual = counts(result.get(0).getStack().getPlacements(), List.of());
		if (!expected.equals(actual)) return false;
		return PlacementSupport.validate(result, supportPolicy).valid()
				&& BottomRuleSupport.isValid(result) && NoPressRuleSupport.isValid(result)
				&& unitCount(merge(moving, removed)) > unitCount(moving);
	}

	private static List<BoxItem> merge(List<BoxItem> moving, List<Placement> removed) {
		Map<String, BoxCount> boxes = new LinkedHashMap<>();
		for (BoxItem item : moving) {
			BoxCount count = boxes.computeIfAbsent(item.getBox().getId(),
					ignored -> new BoxCount(item.getBox()));
			count.count += item.getCount();
		}
		for (Placement placement : removed) {
			Box box = placement.getBox();
			boxes.computeIfAbsent(box.getId(), ignored -> new BoxCount(box)).count++;
		}
		return boxes.values().stream().map(value -> new BoxItem(value.box, value.count)).toList();
	}

	private static Map<String, Integer> counts(List<Placement> placements, List<BoxItem> additions) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Placement placement : placements) counts.merge(placement.getBox().getId(), 1, Integer::sum);
		for (BoxItem item : additions) counts.merge(item.getBox().getId(), item.getCount(), Integer::sum);
		return counts;
	}

	private static int unitCount(List<BoxItem> items) {
		return items.stream().mapToInt(BoxItem::getCount).sum();
	}

	private static long boundedDeadline(long deadlineMillis, long maximumMillis) {
		long candidate = System.currentTimeMillis() + maximumMillis;
		return deadlineMillis <= 0L ? candidate : Math.min(deadlineMillis, candidate);
	}

	private static long sharedDeadline(long deadlineMillis, int remainingCandidates) {
		if (deadlineMillis <= 0L) return System.currentTimeMillis() + 2_000L;
		long remaining = Math.max(1L, deadlineMillis - System.currentTimeMillis());
		return Math.min(deadlineMillis,
				System.currentTimeMillis() + Math.max(1L, remaining / Math.max(1, remainingCandidates)));
	}

	private static boolean expired(long deadlineMillis) {
		return deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
	}

	private static long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private static final class BoxCount {
		private final Box box;
		private int count;

		private BoxCount(Box box) {
			this.box = box;
		}
	}
}
