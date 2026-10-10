package com.github.skjolber.packing.service.service.blockbeam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.skjolber.packing.api.Placement;

final class BlockBeamFixedPlacementIndex {

	private static final int CELL_SIZE = 1_000;
	private final Map<Long, List<Placement>> cells = new HashMap<>();

	BlockBeamFixedPlacementIndex(List<Placement> placements) {
		for (Placement placement : placements) {
			for (int ix = placement.getAbsoluteX() / CELL_SIZE;
					ix <= placement.getAbsoluteEndX() / CELL_SIZE; ix++) {
				for (int iy = placement.getAbsoluteY() / CELL_SIZE;
						iy <= placement.getAbsoluteEndY() / CELL_SIZE; iy++) {
					for (int iz = placement.getAbsoluteZ() / CELL_SIZE;
							iz <= placement.getAbsoluteEndZ() / CELL_SIZE; iz++) {
						cells.computeIfAbsent(key(ix, iy, iz), ignored -> new ArrayList<>()).add(placement);
					}
				}
			}
		}
	}

	boolean intersects(int x, int y, int z, BlockBeamSearchPackager.Block block) {
		int endX = x + block.dx();
		int endY = y + block.dy();
		int endZ = z + block.dz();
		for (int ix = x / CELL_SIZE; ix <= (endX - 1) / CELL_SIZE; ix++) {
			for (int iy = y / CELL_SIZE; iy <= (endY - 1) / CELL_SIZE; iy++) {
				for (int iz = z / CELL_SIZE; iz <= (endZ - 1) / CELL_SIZE; iz++) {
					for (Placement placement : cells.getOrDefault(key(ix, iy, iz), List.of())) {
						if (x < placement.getAbsoluteEndX() + 1 && endX > placement.getAbsoluteX()
								&& y < placement.getAbsoluteEndY() + 1 && endY > placement.getAbsoluteY()
								&& z < placement.getAbsoluteEndZ() + 1 && endZ > placement.getAbsoluteZ()) return true;
					}
				}
			}
		}
		return false;
	}

	private static long key(int x, int y, int z) {
		return ((long) x << 42) | ((long) y << 21) | z;
	}
}
