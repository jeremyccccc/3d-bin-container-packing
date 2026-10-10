package com.github.skjolber.packing.service.service.assignment;

import com.github.skjolber.packing.service.service.mapping.PackingMapper;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxStackValue;

/** Stable, content-based keys used by bounded packing searches. */
public final class PackingDeterminism {

	public static final Comparator<BoxItem> BOX_ITEM_ORDER = Comparator.comparing(PackingDeterminism::boxItemKey);

	private PackingDeterminism() {
	}

	public static String boxItemKey(BoxItem item) {
		Box box = item.getBox();
		String orientations = Arrays.stream(box.getStackValues())
				.map(PackingDeterminism::orientationKey)
				.sorted()
				.collect(Collectors.joining(";"));
		return fields(
				string(box.getProperty(PackingMapper.PROP_HOUSE_BS_ID)),
				string(box.getProperty(PackingMapper.PROP_INBOUND_ID)),
				string(box.getProperty(PackingMapper.PROP_CUSTOMER)),
				string(box.getProperty(PackingMapper.PROP_HEIGHT_POSITION)),
				string(box.getProperty(PackingMapper.PROP_NO_PRESS)),
				string(box.getProperty(PackingMapper.PROP_DOOR_SIDE)),
				string(box.getDescription()),
				Integer.toString(item.getCount()),
				Integer.toString(box.getWeight()),
				orientations);
	}

	private static String orientationKey(BoxStackValue value) {
		return value.getDx() + "x" + value.getDy() + "x" + value.getDz();
	}

	private static String fields(String... values) {
		StringBuilder result = new StringBuilder();
		for (String value : values) result.append(value.length()).append(':').append(value).append('|');
		return result.toString();
	}

	private static String string(Object value) {
		return value == null ? "" : value.toString();
	}
}
