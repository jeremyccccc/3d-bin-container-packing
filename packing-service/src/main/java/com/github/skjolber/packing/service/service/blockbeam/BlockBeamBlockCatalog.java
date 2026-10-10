package com.github.skjolber.packing.service.service.blockbeam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;

final class BlockBeamBlockCatalog {

	private static final int MAX_VERTICAL_UNITS = 5;

	private BlockBeamBlockCatalog() {
	}

	static List<BlockBeamSearchPackager.Block> generate(Container container, List<BoxItem> items) {
		Map<String, BlockBeamSearchPackager.Block> unique = new LinkedHashMap<>();
		for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
			BoxItem item = items.get(itemIndex);
			for (BoxStackValue orientation : item.getBox().getStackValues()) {
				int maxX = Math.min(item.getCount(), container.getLoadDx() / orientation.getDx());
				int maxY = Math.min(item.getCount(), container.getLoadDy() / orientation.getDy());
				int maxZ = Math.min(Math.min(item.getCount(), MAX_VERTICAL_UNITS),
						container.getLoadDz() / orientation.getDz());
				if (maxX == 0 || maxY == 0 || maxZ == 0) continue;
				for (int z = 1; z <= maxZ; z++) {
					for (int y = 1; y <= maxY; y++) {
						int x = Math.min(maxX, item.getCount() / (y * z));
						add(unique, itemIndex, item.getBox(), orientation, x, y, z);
						add(unique, itemIndex, item.getBox(), orientation, 1, y, z);
					}
					for (int x = 1; x <= maxX; x++) {
						int y = Math.min(maxY, item.getCount() / (x * z));
						add(unique, itemIndex, item.getBox(), orientation, x, y, z);
					}
				}
			}
		}
		List<BlockBeamSearchPackager.Block> blocks = new ArrayList<>(unique.values());
		blocks.sort(Comparator.comparingInt(BlockBeamSearchPackager.Block::units).reversed()
				.thenComparing(Comparator.comparingLong(BlockBeamSearchPackager.Block::volume).reversed())
				.thenComparingInt(BlockBeamSearchPackager.Block::dz));
		return blocks;
	}

	private static void add(Map<String, BlockBeamSearchPackager.Block> blocks, int itemIndex, Box box,
			BoxStackValue orientation, int nx, int ny, int nz) {
		if (nx < 1 || ny < 1 || nz < 1) return;
		int units = nx * ny * nz;
		String key = itemIndex + ":" + orientation.getIndex() + ":" + nx + ":" + ny + ":" + nz;
		blocks.putIfAbsent(key, new BlockBeamSearchPackager.Block(itemIndex, box, orientation, nx, ny, nz,
				units, orientation.getDx() * nx, orientation.getDy() * ny,
				orientation.getDz() * nz, Math.multiplyExact(box.getWeight(), units),
				Math.multiplyExact(box.getVolume(), units)));
	}
}
