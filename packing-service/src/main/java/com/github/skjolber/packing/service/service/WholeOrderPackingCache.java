package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

/** Request-scoped cache for complete one-container packing attempts. */
final class WholeOrderPackingCache {

	private final Map<Key, Entry> entries = new HashMap<>();
	private int hits;
	private int misses;
	private int successHits;
	private int failureHits;
	private long savedMillis;

	Lookup lookup(Container target, List<BoxItem> items) {
		Entry entry = entries.get(key(target, items));
		if (entry == null) {
			misses++;
			return Lookup.miss();
		}
		hits++;
		savedMillis += entry.computationMillis();
		if (entry.result() == null) {
			failureHits++;
			return Lookup.failure(entry.computationMillis());
		}
		successHits++;
		return Lookup.success(rebind(entry.result(), target.getId()), entry.computationMillis());
	}

	void putSuccess(Container container, List<BoxItem> items, PackagerResult result, long computationMillis) {
		entries.putIfAbsent(key(container, items), new Entry(result, computationMillis));
	}

	void putFailure(Container container, List<BoxItem> items, long computationMillis, boolean completed) {
		if (completed) {
			entries.putIfAbsent(key(container, items), new Entry(null, computationMillis));
		}
	}

	Stats stats() {
		return new Stats(hits, misses, successHits, failureHits, entries.size(), savedMillis);
	}

	private static Key key(Container container, List<BoxItem> items) {
		ContainerKey containerKey = new ContainerKey(container.getDx(), container.getDy(), container.getDz(),
				container.getLoadDx(), container.getLoadDy(), container.getLoadDz(),
				container.getEmptyWeight(), container.getMaxLoadWeight());
		List<ItemKey> itemKeys = new ArrayList<>(items.size());
		for (BoxItem item : items) {
			Box box = item.getBox();
			List<DimensionKey> orientations = new ArrayList<>(box.getStackValues().length);
			for (BoxStackValue value : box.getStackValues()) {
				orientations.add(new DimensionKey(value.getDx(), value.getDy(), value.getDz()));
			}
			orientations.sort(Comparator.comparingInt(DimensionKey::dx)
					.thenComparingInt(DimensionKey::dy).thenComparingInt(DimensionKey::dz));
			itemKeys.add(new ItemKey(
					string(box.getId()),
					string(box.getProperty(PackingMapper.PROP_CARGO_ID)),
					string(box.getProperty(PackingMapper.PROP_HOUSE_BS_ID)),
					string(box.getProperty(PackingMapper.PROP_INBOUND_ID)),
					item.getCount(), box.getWeight(), List.copyOf(orientations),
					string(box.getProperty(PackingMapper.PROP_CUSTOMER)),
					string(box.getProperty(PackingMapper.PROP_HEIGHT_POSITION)),
					string(box.getProperty(PackingMapper.PROP_NO_PRESS)),
					string(box.getProperty(PackingMapper.PROP_DOOR_SIDE))));
		}
		itemKeys.sort(Comparator.comparing(ItemKey::sortKey));
		return new Key(containerKey, List.copyOf(itemKeys));
	}

	private static String string(Object value) {
		return value == null ? "" : value.toString();
	}

	private static PackagerResult rebind(PackagerResult cached, String targetContainerId) {
		Container template = cached.get(0);
		Container result = Container.newBuilder()
				.withId(targetContainerId)
				.withDescription(template.getDescription())
				.withSize(template.getDx(), template.getDy(), template.getDz())
				.withLoadSize(template.getLoadDx(), template.getLoadDy(), template.getLoadDz())
				.withEmptyWeight(template.getEmptyWeight())
				.withMaxLoadWeight(template.getMaxLoadWeight())
				.build();
		for (Placement placement : template.getStack().getPlacements()) {
			result.getStack().add(new Placement(placement.getStackValue(), placement.getPointIndex(),
					placement.getAbsoluteX(), placement.getAbsoluteY(), placement.getAbsoluteZ()));
		}
		return new PackagerResult(List.of(result), cached.getDuration(), false);
	}

	record Lookup(boolean hit, boolean failed, PackagerResult result, long savedMillis) {
		private static Lookup miss() {
			return new Lookup(false, false, null, 0L);
		}

		private static Lookup failure(long savedMillis) {
			return new Lookup(true, true, null, savedMillis);
		}

		private static Lookup success(PackagerResult result, long savedMillis) {
			return new Lookup(true, false, result, savedMillis);
		}
	}

	record Stats(int hits, int misses, int successHits, int failureHits, int size, long savedMillis) {
	}

	private record Entry(PackagerResult result, long computationMillis) {
	}

	private record Key(ContainerKey container, List<ItemKey> items) {
	}

	private record ContainerKey(int dx, int dy, int dz, int loadDx, int loadDy, int loadDz,
			int emptyWeight, int maxLoadWeight) {
	}

	private record DimensionKey(int dx, int dy, int dz) {
	}

	private record ItemKey(String boxId, String cargoId, String houseBillId, String inboundId,
			int count, int weight, List<DimensionKey> orientations,
			String customer, String heightPosition, String noPress, String doorSide) {
		private String sortKey() {
			return boxId + '\u0000' + cargoId + '\u0000' + houseBillId + '\u0000' + inboundId
					+ '\u0000' + count + '\u0000' + weight + '\u0000' + orientations
					+ '\u0000' + customer + '\u0000' + heightPosition
					+ '\u0000' + noPress + '\u0000' + doorSide;
		}
	}
}
