package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.Rotation;

/** Builds fixed-orientation rows and expands successful rows back to units. */
final class MacroBlockPlanner {

	private static final String MACRO_PREFIX = "__macro__";

	private MacroBlockPlanner() {
	}

	static Plan rowsAcrossY(List<BoxItem> source, Container container) {
		return rowsAcrossY(source, container, Integer.MAX_VALUE);
	}

	static Plan rowsAcrossY(List<BoxItem> source, Container container, int maximumUnitsPerRow) {
		return rowsAcrossY(source, container, maximumUnitsPerRow, Integer.MAX_VALUE);
	}

	static Plan rowsAcrossY(List<BoxItem> source, Container container, int maximumUnitsPerRow,
			int maximumMacroTypes) {
		if (maximumUnitsPerRow < 2) {
			throw new IllegalArgumentException("A macro row must contain at least two units");
		}
		return blocks(source, container, 1, maximumUnitsPerRow, 1, true, maximumMacroTypes);
	}

	static Plan grid(List<BoxItem> source, Container container, int unitsX, int unitsY) {
		return grid(source, container, unitsX, unitsY, Integer.MAX_VALUE);
	}

	static Plan grid(List<BoxItem> source, Container container, int unitsX, int unitsY,
			int maximumMacroTypes) {
		if (unitsX < 1 || unitsY < 1 || unitsX * unitsY < 2) {
			throw new IllegalArgumentException("A macro grid must contain at least two units");
		}
		return blocks(source, container, unitsX, unitsY, 1, false, maximumMacroTypes);
	}

	static Plan cuboid(List<BoxItem> source, Container container, int unitsX, int unitsY, int unitsZ,
			int maximumMacroTypes) {
		if (unitsX < 1 || unitsY < 1 || unitsZ < 1 || unitsX * unitsY * unitsZ < 2) {
			throw new IllegalArgumentException("A macro cuboid must contain at least two units");
		}
		return blocks(source, container, unitsX, unitsY, unitsZ, false, maximumMacroTypes);
	}

	/**
	 * Builds one stable block shape per cargo type. The shape producing the fewest
	 * packer objects (complete blocks plus loose remainder units) is preferred.
	 * This avoids replacing a useful large block with many tiny blocks merely to
	 * obtain an exact divisor.
	 */
	static Plan adaptiveCuboids(List<BoxItem> source, Container container) {
		return adaptiveCuboids(source, container, 2);
	}

	static Plan adaptiveCuboids(List<BoxItem> source, Container container, int minimumItemCount) {
		int[][] shapes = {
				{2, 2, 5}, {1, 4, 5}, {1, 2, 5}, {1, 1, 5},
				{2, 2, 4}, {1, 4, 4}, {1, 2, 4}, {1, 1, 4},
				{2, 2, 3}, {1, 4, 3}, {1, 2, 3}, {1, 1, 3},
				{2, 2, 2}, {1, 4, 2}, {1, 2, 2}, {1, 1, 2}
		};
		List<BoxItem> items = new ArrayList<>();
		Map<String, Block> blocks = new LinkedHashMap<>();
		int sequence = 0;
		for (BoxItem item : source) {
			if (item.getCount() < minimumItemCount) {
				items.add(new BoxItem(item.getBox(), item.getCount()));
				continue;
			}
			AdaptiveBlock best = null;
			for (int[] shape : shapes) {
				int units = shape[0] * shape[1] * shape[2];
				if (units > item.getCount()) continue;
				BoxStackValue orientation = bestOrientation(item.getBox(), container,
						shape[0], shape[1], shape[2], false);
				if (orientation == null) continue;
				int remainder = item.getCount() % units;
				int objectCount = item.getCount() / units + remainder;
				if (best == null || objectCount < best.objectCount()
						|| objectCount == best.objectCount() && remainder < best.remainder()
						|| objectCount == best.objectCount() && remainder == best.remainder()
								&& units > best.units()) {
					best = new AdaptiveBlock(orientation, shape[0], shape[1], shape[2], units,
							remainder, objectCount);
				}
			}
			if (best == null) {
				items.add(new BoxItem(item.getBox(), item.getCount()));
				continue;
			}
			String macroId = MACRO_PREFIX + "adaptive_" + best.unitsX() + "x" + best.unitsY()
					+ "x" + best.unitsZ() + "__" + sequence++ + "__" + item.getBox().getId();
			BoxStackValue rotatedOrientation = rotated(best.orientation(), item.getBox());
			Box macro = macroBox(macroId, item.getBox(), best.orientation(),
					best.unitsX(), best.unitsY(), best.unitsZ(), rotatedOrientation != null);
			items.add(new BoxItem(macro, item.getCount() / best.units()));
			blocks.put(macroId, new Block(item.getBox(), best.orientation(),
					rotatedOrientation, best.unitsX(), best.unitsY(), best.unitsZ()));
			if (best.remainder() > 0) {
				items.add(new BoxItem(item.getBox(), best.remainder()));
			}
		}
		return new Plan(items, blocks);
	}

	private static Plan blocks(List<BoxItem> source, Container container,
			int requestedUnitsX, int requestedUnitsY, int requestedUnitsZ, boolean capYToContainer,
			int maximumMacroTypes) {
		Set<Box> selected = source.stream()
				.sorted(Comparator.<BoxItem>comparingInt(BoxItem::getCount).reversed()
						.thenComparing(Comparator.comparingLong(BoxItem::getVolume).reversed()))
				.limit(maximumMacroTypes)
				.map(BoxItem::getBox)
				.collect(Collectors.toSet());
		List<BoxItem> items = new ArrayList<>();
		Map<String, Block> blocks = new LinkedHashMap<>();
		int sequence = 0;
		for (BoxItem item : source) {
			if (!selected.contains(item.getBox())) {
				items.add(new BoxItem(item.getBox(), item.getCount()));
				continue;
			}
			BoxStackValue orientation = bestOrientation(item.getBox(), container,
					requestedUnitsX, requestedUnitsY, requestedUnitsZ, capYToContainer);
			int unitsX = orientation == null ? 1 : requestedUnitsX;
			int unitsY = orientation == null ? 1 : capYToContainer
					? Math.min(requestedUnitsY, container.getLoadDy() / orientation.getDy())
					: requestedUnitsY;
			int unitsZ = orientation == null ? 1 : requestedUnitsZ;
			int unitsPerBlock = unitsX * unitsY * unitsZ;
			if (unitsPerBlock < 2 || item.getCount() < unitsPerBlock) {
				items.add(new BoxItem(item.getBox(), item.getCount()));
				continue;
			}

			int completeBlocks = item.getCount() / unitsPerBlock;
			int remainder = item.getCount() % unitsPerBlock;
			String macroId = MACRO_PREFIX + unitsX + "x" + unitsY + "x" + unitsZ
					+ "__" + sequence++ + "__" + item.getBox().getId();
			BoxStackValue rotatedOrientation = rotated(orientation, item.getBox());
			Box macro = macroBox(macroId, item.getBox(), orientation, unitsX, unitsY, unitsZ,
					rotatedOrientation != null);
			items.add(new BoxItem(macro, completeBlocks));
			blocks.put(macroId, new Block(item.getBox(), orientation, rotatedOrientation,
					unitsX, unitsY, unitsZ));
			if (remainder > 0) {
				items.add(new BoxItem(item.getBox(), remainder));
			}
		}
		return new Plan(items, blocks);
	}

	private static BoxStackValue bestOrientation(Box box, Container container,
			int requestedUnitsX, int requestedUnitsY, int requestedUnitsZ,
			boolean capYToContainer) {
		BoxStackValue best = null;
		int bestFilledWidth = -1;
		int bestHeight = Integer.MAX_VALUE;
		for (BoxStackValue value : box.getStackValues()) {
			if (!container.canLoad(value)) continue;
			int unitsY = capYToContainer
					? Math.min(requestedUnitsY, container.getLoadDy() / value.getDy())
					: requestedUnitsY;
			if (requestedUnitsX * value.getDx() > container.getLoadDx()
					|| unitsY * value.getDy() > container.getLoadDy()
					|| requestedUnitsZ * value.getDz() > container.getLoadDz()
					|| requestedUnitsX * unitsY * requestedUnitsZ < 2) continue;
			int filledWidth = unitsY * value.getDy();
			if (value.getDz() < bestHeight
					|| value.getDz() == bestHeight && filledWidth > bestFilledWidth) {
				best = value;
				bestFilledWidth = filledWidth;
				bestHeight = value.getDz();
			}
		}
		return best;
	}

	private static BoxStackValue rotated(BoxStackValue orientation, Box original) {
		for (BoxStackValue value : original.getStackValues()) {
			if (value.getDx() == orientation.getDy() && value.getDy() == orientation.getDx()
					&& value.getDz() == orientation.getDz()) return value;
		}
		return null;
	}

	private static Box macroBox(String id, Box original, BoxStackValue orientation,
			int unitsX, int unitsY, int unitsZ, boolean rotateInPlane) {
		Rotation rotation = rotateInPlane ? Rotation.newBuilder().withBottom().build()
				: Rotation.newBuilder().withBottomAtZeroDegrees().build();
		Box.Builder builder = Box.newBuilder()
				.withId(id)
				.withDescription(original.getDescription())
				.withSize(orientation.getDx() * unitsX, orientation.getDy() * unitsY,
						orientation.getDz() * unitsZ)
				.withWeight(Math.multiplyExact(original.getWeight(), unitsX * unitsY * unitsZ))
				.withRotation(rotation);
		copyProperty(builder, original, PackingMapper.PROP_CARGO_ID);
		copyProperty(builder, original, PackingMapper.PROP_HOUSE_BS_ID);
		copyProperty(builder, original, PackingMapper.PROP_INBOUND_ID);
		copyProperty(builder, original, PackingMapper.PROP_CUSTOMER);
		copyProperty(builder, original, PackingMapper.PROP_HEIGHT_POSITION);
		copyProperty(builder, original, PackingMapper.PROP_NO_PRESS);
		copyProperty(builder, original, PackingMapper.PROP_DOOR_SIDE);
		return builder.build();
	}

	private static void copyProperty(Box.Builder builder, Box original, String key) {
		Object value = original.getProperty(key);
		if (value != null) {
			builder.withProperty(key, value);
		}
	}

	record Plan(List<BoxItem> boxItems, Map<String, Block> blocks) {
		boolean hasMacros() {
			return !blocks.isEmpty();
		}

		PackagerResult expand(PackagerResult result) {
			for (Container container : result.getContainers()) {
				List<Placement> expanded = new ArrayList<>();
				for (Placement placement : container.getStack().getPlacements()) {
					Block block = blocks.get(placement.getBox().getId());
					if (block == null) {
						expanded.add(placement);
						continue;
					}
					boolean rotated = block.rotatedOrientation() != null
							&& placement.getStackValue().getDx() != block.orientation().getDx() * block.unitsX()
							&& placement.getStackValue().getDx() == block.orientation().getDy() * block.unitsY();
					BoxStackValue unit = rotated ? block.rotatedOrientation() : block.orientation();
					int countX = rotated ? block.unitsY() : block.unitsX();
					int countY = rotated ? block.unitsX() : block.unitsY();
					for (int x = 0; x < countX; x++) {
						for (int y = 0; y < countY; y++) {
							for (int z = 0; z < block.unitsZ(); z++) {
								expanded.add(new Placement(unit, -1,
										placement.getAbsoluteX() + x * unit.getDx(),
										placement.getAbsoluteY() + y * unit.getDy(),
										placement.getAbsoluteZ() + z * unit.getDz()));
							}
						}
					}
				}
				container.getStack().clear();
				container.getStack().addAll(expanded);
			}
			return result;
		}
	}

	record Block(Box original, BoxStackValue orientation, BoxStackValue rotatedOrientation,
			int unitsX, int unitsY, int unitsZ) {
	}

	private record AdaptiveBlock(BoxStackValue orientation, int unitsX, int unitsY, int unitsZ,
			int units, int remainder, int objectCount) {
	}
}
