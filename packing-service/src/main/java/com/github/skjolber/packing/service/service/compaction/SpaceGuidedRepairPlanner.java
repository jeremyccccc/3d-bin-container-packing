package com.github.skjolber.packing.service.service.compaction;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;
import com.github.skjolber.packing.ep.points3d.DefaultPointCalculator3D;
import com.github.skjolber.packing.service.service.blockbeam.BlockBeamSearchPackager;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SpaceGuidedRepairPlanner {

	private static final int MAX_PATTERNS = 6;

	private static final Comparator<Candidate> CANDIDATE_ORDER =
			Comparator.comparingLong(Candidate::blockerVolume)
					.thenComparingInt(Candidate::directBlockers)
					.thenComparingLong(Candidate::expansionVolume)
					.thenComparing(Comparator.comparingLong(Candidate::sourceSpaceVolume).reversed());

	private final PlacementSupport.Policy supportPolicy;
	private final int beamWidth;
	private final int branching;

	SpaceGuidedRepairPlanner(PlacementSupport.Policy supportPolicy, int beamWidth, int branching) {
		this.supportPolicy = supportPolicy;
		this.beamWidth = beamWidth;
		this.branching = branching;
	}

	List<RequiredShape> compactPatterns(Container target, List<BoxItem> movingItems,
			long deadlineMillis, String logContext) {
		BlockBeamSearchPackager packager = new BlockBeamSearchPackager(
				beamWidth, branching, supportPolicy,
				BlockBeamSearchPackager.SearchProfile.BALANCED);
		PackagerResult compact = packager.pack(target, movingItems, deadlineMillis,
				logContext + " phase=compact-bill-pattern");
		RequiredShape packedShape = compact == null || !compact.isSuccess()
				? fallbackShape(target, movingItems)
				: bounds(compact.get(0).getStack().getPlacements());
		if (packedShape == null) return List.of();

		Set<RequiredShape> variants = new LinkedHashSet<>();
		int x = packedShape.dx();
		int y = packedShape.dy();
		int z = packedShape.dz();
		addPattern(variants, target, x, y, z);
		addPattern(variants, target, y, x, z);
		addPattern(variants, target, x, z, y);
		addPattern(variants, target, z, x, y);
		addPattern(variants, target, y, z, x);
		addPattern(variants, target, z, y, x);
		return variants.stream().limit(MAX_PATTERNS).toList();
	}

	List<Candidate> candidates(Container target, List<RequiredShape> patterns) {
		List<Placement> placements = target.getStack().getPlacements();
		List<OpenSpace> spaces = openSpaces(target, placements);
		Map<String, Candidate> candidates = new LinkedHashMap<>();
		for (RequiredShape pattern : patterns) {
			for (OpenSpace space : spaces) {
				int[] xs = anchors(space.x(), space.endX(), pattern.dx(), target.getLoadDx());
				int[] ys = anchors(space.y(), space.endY(), pattern.dy(), target.getLoadDy());
				int[] zs = anchors(space.z(), space.endZ(), pattern.dz(), target.getLoadDz());
				for (int x : xs) for (int y : ys) for (int z : zs) {
					Region region = new Region(x, y, z, pattern.dx(), pattern.dy(), pattern.dz());
					List<Placement> blockers = placements.stream()
							.filter(region::intersects).toList();
					if (blockers.isEmpty()) continue;
					long blockerVolume = blockers.stream()
							.mapToLong(placement -> placement.getStackValue().getVolume()).sum();
					long expansion = Math.max(0L, region.volume() - space.overlapVolume(region));
					Candidate candidate = new Candidate(pattern, region, blockers.size(),
							blockerVolume, expansion, space.volume());
					String blockerKey = blockers.stream()
							.sorted(Comparator.comparing(Placement::getAbsoluteZ)
									.thenComparingInt(Placement::getAbsoluteY)
									.thenComparingInt(Placement::getAbsoluteX)
									.thenComparing(placement -> placement.getBox().getId()))
							.map(placement -> placement.getBox().getId() + '@'
									+ placement.getAbsoluteX() + ':' + placement.getAbsoluteY()
									+ ':' + placement.getAbsoluteZ())
							.reduce((left, right) -> left + '|' + right).orElse("");
					candidates.merge(blockerKey, candidate,
							(left, right) -> CANDIDATE_ORDER.compare(left, right) <= 0 ? left : right);
				}
			}
		}
		return candidates.values().stream().sorted(CANDIDATE_ORDER).toList();
	}

	Neighborhood neighborhood(Container target, Region region) {
		List<Placement> fixed = new ArrayList<>();
		List<Placement> removed = new ArrayList<>();
		for (Placement placement : target.getStack().getPlacements()) {
			if (region.intersects(placement)) removed.add(placement);
			else fixed.add(placement);
		}
		boolean changed;
		do {
			changed = false;
			for (int index = fixed.size() - 1; index >= 0; index--) {
				Placement placement = fixed.get(index);
				if (placement.getAbsoluteZ() == 0
						|| PlacementSupport.validate(placement, fixed, supportPolicy).valid()) continue;
				removed.add(placement);
				fixed.remove(index);
				changed = true;
			}
		} while (changed);
		return new Neighborhood(List.copyOf(fixed), List.copyOf(removed));
	}

	private static RequiredShape bounds(List<Placement> placements) {
		if (placements.isEmpty()) return null;
		int minX = placements.stream().mapToInt(Placement::getAbsoluteX).min().orElse(0);
		int minY = placements.stream().mapToInt(Placement::getAbsoluteY).min().orElse(0);
		int minZ = placements.stream().mapToInt(Placement::getAbsoluteZ).min().orElse(0);
		int maxX = placements.stream().mapToInt(placement -> placement.getAbsoluteEndX() + 1).max().orElse(0);
		int maxY = placements.stream().mapToInt(placement -> placement.getAbsoluteEndY() + 1).max().orElse(0);
		int maxZ = placements.stream().mapToInt(placement -> placement.getAbsoluteEndZ() + 1).max().orElse(0);
		return new RequiredShape(maxX - minX, maxY - minY, maxZ - minZ);
	}

	private static RequiredShape fallbackShape(Container target, List<BoxItem> items) {
		long volume = items.stream().mapToLong(item ->
				item.getBox().getVolume() * (long) item.getCount()).sum();
		int maxX = items.stream().mapToInt(item -> item.getBox().getStackValues()[0].getDx()).max().orElse(1);
		int maxY = items.stream().mapToInt(item -> item.getBox().getStackValues()[0].getDy()).max().orElse(1);
		int maxZ = items.stream().mapToInt(item -> item.getBox().getStackValues()[0].getDz()).max().orElse(1);
		int dx = Math.max(maxX, Math.min(target.getLoadDx(), (int) Math.ceil(Math.cbrt(volume))));
		int dy = Math.max(maxY, Math.min(target.getLoadDy(),
				(int) Math.ceil(Math.sqrt(volume / (double) Math.max(1, dx)))));
		int dz = Math.max(maxZ, (int) Math.ceil(volume / (double) Math.max(1L, (long) dx * dy)));
		return dz <= target.getLoadDz() ? new RequiredShape(dx, dy, dz) : null;
	}

	private static void addPattern(Set<RequiredShape> patterns, Container target,
			int dx, int dy, int dz) {
		if (dx <= target.getLoadDx() && dy <= target.getLoadDy() && dz <= target.getLoadDz()) {
			patterns.add(new RequiredShape(dx, dy, dz));
		}
	}

	private static List<OpenSpace> openSpaces(Container target, List<Placement> placements) {
		List<Placement> ordered = placements.stream()
				.sorted(Comparator.comparingInt(Placement::getAbsoluteZ)
						.thenComparingInt(Placement::getAbsoluteY)
						.thenComparingInt(Placement::getAbsoluteX)).toList();
		DefaultPointCalculator3D calculator = new DefaultPointCalculator3D(false, ordered.size() + 1);
		calculator.clearToSize(target.getLoadDx(), target.getLoadDy(), target.getLoadDz());
		for (Placement placement : ordered) {
			if (!calculator.addObstacle(placement)) return List.of();
		}
		List<OpenSpace> spaces = new ArrayList<>();
		for (Point point : calculator.getAll()) {
			spaces.add(new OpenSpace(point.getMinX(), point.getMinY(), point.getMinZ(),
					point.getDx(), point.getDy(), point.getDz()));
		}
		return spaces.stream().sorted(Comparator.comparingLong(OpenSpace::volume).reversed()).toList();
	}

	private static int[] anchors(int min, int end, int required, int limit) {
		int atMin = Math.max(0, Math.min(min, limit - required));
		int atEnd = Math.max(0, Math.min(end - required, limit - required));
		return atMin == atEnd ? new int[] {atMin} : new int[] {atMin, atEnd};
	}

	record RequiredShape(int dx, int dy, int dz) {
		String key() {
			return dx + "x" + dy + "x" + dz;
		}
	}

	record Region(int x, int y, int z, int dx, int dy, int dz) {
		int endX() { return x + dx; }
		int endY() { return y + dy; }
		int endZ() { return z + dz; }
		long volume() { return (long) dx * dy * dz; }

		boolean intersects(Placement placement) {
			return x < placement.getAbsoluteEndX() + 1 && endX() > placement.getAbsoluteX()
					&& y < placement.getAbsoluteEndY() + 1 && endY() > placement.getAbsoluteY()
					&& z < placement.getAbsoluteEndZ() + 1 && endZ() > placement.getAbsoluteZ();
		}
	}

	private record OpenSpace(int x, int y, int z, int dx, int dy, int dz) {
		int endX() { return x + dx; }
		int endY() { return y + dy; }
		int endZ() { return z + dz; }
		long volume() { return (long) dx * dy * dz; }

		long overlapVolume(Region region) {
			int overlapX = Math.max(0, Math.min(endX(), region.endX()) - Math.max(x, region.x()));
			int overlapY = Math.max(0, Math.min(endY(), region.endY()) - Math.max(y, region.y()));
			int overlapZ = Math.max(0, Math.min(endZ(), region.endZ()) - Math.max(z, region.z()));
			return (long) overlapX * overlapY * overlapZ;
		}
	}

	record Candidate(RequiredShape pattern, Region region, int directBlockers,
			long blockerVolume, long expansionVolume, long sourceSpaceVolume) {
	}

	record Neighborhood(List<Placement> fixed, List<Placement> removed) {
	}
}
