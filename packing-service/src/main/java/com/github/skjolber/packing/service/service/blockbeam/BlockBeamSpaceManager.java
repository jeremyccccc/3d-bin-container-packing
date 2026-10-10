package com.github.skjolber.packing.service.service.blockbeam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.point.Point;
import com.github.skjolber.packing.ep.points3d.DefaultPointCalculator3D;

final class BlockBeamSpaceManager {

	private BlockBeamSpaceManager() {
	}

	static List<BlockBeamSearchPackager.Space> initialSpaces(Container container,
			List<Placement> fixedPlacements) {
		if (fixedPlacements.isEmpty()) {
			return List.of(new BlockBeamSearchPackager.Space(0, 0, 0,
					container.getLoadDx(), container.getLoadDy(), container.getLoadDz()));
		}
		List<Placement> ordered = fixedPlacements.stream()
				.sorted(Comparator.comparingInt(Placement::getAbsoluteZ)
						.thenComparingInt(Placement::getAbsoluteY)
						.thenComparingInt(Placement::getAbsoluteX))
				.toList();
		DefaultPointCalculator3D calculator = new DefaultPointCalculator3D(false, ordered.size() + 1);
		calculator.clearToSize(container.getLoadDx(), container.getLoadDy(), container.getLoadDz());
		for (int i = 0; i < ordered.size(); i++) {
			if (!calculator.addObstacle(ordered.get(i))) {
				throw new IllegalArgumentException("Unable to add fixed placement obstacle #" + i);
			}
		}
		List<BlockBeamSearchPackager.Space> spaces = new ArrayList<>();
		for (Point point : calculator.getAll()) {
			addSpace(spaces, point.getMinX(), point.getMinY(), point.getMinZ(),
					point.getDx(), point.getDy(), point.getDz());
		}
		return normalize(spaces);
	}

	static List<BlockBeamSearchPackager.Space> updateSpaces(
			List<BlockBeamSearchPackager.Space> source, int x, int y, int z,
			BlockBeamSearchPackager.Block block, Container container) {
		int endX = x + block.dx();
		int endY = y + block.dy();
		int endZ = z + block.dz();
		List<BlockBeamSearchPackager.Space> spaces = new ArrayList<>();
		for (BlockBeamSearchPackager.Space space : source) {
			if (!space.intersects(x, y, z, endX, endY, endZ)) {
				spaces.add(space);
				continue;
			}
			int intersectionMinX = Math.max(space.x(), x);
			int intersectionMaxX = Math.min(space.endX(), endX);
			int intersectionMinY = Math.max(space.y(), y);
			int intersectionMaxY = Math.min(space.endY(), endY);
			int intersectionMinZ = Math.max(space.z(), z);
			addSpace(spaces, space.x(), space.y(), space.z(),
					intersectionMinX - space.x(), space.dy(), space.dz());
			addSpace(spaces, intersectionMaxX, space.y(), space.z(),
					space.endX() - intersectionMaxX, space.dy(), space.dz());
			addSpace(spaces, space.x(), space.y(), space.z(),
					space.dx(), intersectionMinY - space.y(), space.dz());
			addSpace(spaces, space.x(), intersectionMaxY, space.z(),
					space.dx(), space.endY() - intersectionMaxY, space.dz());
			addSpace(spaces, space.x(), space.y(), space.z(),
					space.dx(), space.dy(), intersectionMinZ - space.z());
		}
		addSpace(spaces, x, y, endZ, block.dx(), block.dy(), container.getLoadDz() - endZ);
		return normalize(spaces);
	}

	private static void addSpace(List<BlockBeamSearchPackager.Space> spaces,
			int x, int y, int z, int dx, int dy, int dz) {
		if (dx > 0 && dy > 0 && dz > 0) {
			spaces.add(new BlockBeamSearchPackager.Space(x, y, z, dx, dy, dz));
		}
	}

	private static List<BlockBeamSearchPackager.Space> normalize(
			List<BlockBeamSearchPackager.Space> input) {
		List<BlockBeamSearchPackager.Space> spaces = new ArrayList<>(new LinkedHashMap<>(input.stream()
				.collect(Collectors.toMap(BlockBeamSearchPackager.Space::key, space -> space,
						(first, duplicate) -> first, LinkedHashMap::new))).values());
		for (int i = spaces.size() - 1; i >= 0; i--) {
			BlockBeamSearchPackager.Space candidate = spaces.get(i);
			for (int j = 0; j < spaces.size(); j++) {
				if (i != j && spaces.get(j).contains(candidate)) {
					spaces.remove(i);
					break;
				}
			}
		}
		spaces.sort(BlockBeamSearchPackager.Space.ORDER);
		return spaces;
	}
}
