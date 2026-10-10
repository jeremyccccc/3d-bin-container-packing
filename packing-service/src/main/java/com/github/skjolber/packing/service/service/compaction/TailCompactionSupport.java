package com.github.skjolber.packing.service.service.compaction;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.service.service.mapping.PackingMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TailCompactionSupport {

	private TailCompactionSupport() {
	}

	static int leastFilledContainer(List<Container> containers) {
		int index = 0;
		double fill = Double.MAX_VALUE;
		for (int i = 0; i < containers.size(); i++) {
			Container container = containers.get(i);
			double candidate = loadedVolume(container) / (double) container.getMaxLoadVolume();
			if (candidate < fill) {
				fill = candidate;
				index = i;
			}
		}
		return index;
	}

	static List<Integer> targetOrder(List<Container> containers, int tailIndex) {
		List<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < containers.size(); i++) if (i != tailIndex) indexes.add(i);
		indexes.sort(Comparator.<Integer>comparingDouble(index ->
				loadedVolume(containers.get(index)) / (double) containers.get(index).getMaxLoadVolume()).reversed()
				.thenComparingInt(Integer::intValue));
		return indexes;
	}

	static List<HouseBillLoad> houseBills(Container tail) {
		Map<String, BillBuilder> bills = new LinkedHashMap<>();
		for (Placement placement : tail.getStack().getPlacements()) {
			Object property = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			String houseBsId = String.valueOf(property);
			bills.computeIfAbsent(houseBsId, BillBuilder::new).add(placement);
		}
		return bills.values().stream().map(BillBuilder::build).toList();
	}

	static List<BoxItem> boxItems(List<Placement> placements) {
		Map<String, BoxCount> boxes = new LinkedHashMap<>();
		for (Placement placement : placements) {
			Box box = placement.getBox();
			boxes.computeIfAbsent(box.getId(), ignored -> new BoxCount(box)).count++;
		}
		return boxes.values().stream().map(value -> new BoxItem(value.box, value.count)).toList();
	}

	static Container withoutHouseBill(Container source, String houseBsId) {
		Container copy = source.clone();
		for (Placement placement : source.getStack().getPlacements()) {
			Object property = placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			if (!houseBsId.equals(String.valueOf(property))) copy.getStack().add(placement);
		}
		return copy;
	}

	static long loadedVolume(Container container) {
		long volume = 0L;
		for (Placement placement : container.getStack().getPlacements()) {
			volume += placement.getStackValue().getVolume();
		}
		return volume;
	}

	static boolean canRepack(List<Placement> placements) {
		for (Placement placement : placements) {
			if (!hasSimpleRules(placement.getBox())) return false;
		}
		return true;
	}

	static boolean canPack(List<BoxItem> items) {
		for (BoxItem item : items) if (!hasSimpleRules(item.getBox())) return false;
		return true;
	}

	private static boolean hasSimpleRules(Box box) {
		Object noPress = box.getProperty(PackingMapper.PROP_NO_PRESS);
		Object doorSide = box.getProperty(PackingMapper.PROP_DOOR_SIDE);
		Object heightPosition = box.getProperty(PackingMapper.PROP_HEIGHT_POSITION);
		return !Boolean.TRUE.equals(noPress) && !Boolean.TRUE.equals(doorSide)
				&& (!(heightPosition instanceof Number number) || number.intValue() == 0);
	}

	record HouseBillLoad(String houseBsId, List<BoxItem> items, int units, long volume) {
	}

	private static final class BillBuilder {
		private final String houseBsId;
		private final Map<String, BoxCount> boxes = new LinkedHashMap<>();
		private int units;
		private long volume;

		private BillBuilder(String houseBsId) {
			this.houseBsId = houseBsId;
		}

		private void add(Placement placement) {
			Box box = placement.getBox();
			boxes.computeIfAbsent(box.getId(), ignored -> new BoxCount(box)).count++;
			units++;
			volume += placement.getStackValue().getVolume();
		}

		private HouseBillLoad build() {
			List<BoxItem> items = boxes.values().stream()
					.map(value -> new BoxItem(value.box, value.count)).toList();
			return new HouseBillLoad(houseBsId, items, units, volume);
		}
	}

	private static final class BoxCount {
		private final Box box;
		private int count;

		private BoxCount(Box box) {
			this.box = box;
		}
	}
}
