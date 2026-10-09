package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.service.service.mapping.PackingMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.skjolber.packing.api.BoxItem;

final class PackingDiagnostics {

	private PackingDiagnostics() {
	}

	static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	static int unitCount(List<BoxItem> items) {
		return items.stream().mapToInt(BoxItem::getCount).sum();
	}

	static long totalVolume(List<BoxItem> items) {
		return items.stream().mapToLong(BoxItem::getVolume).sum();
	}

	static long totalWeight(List<BoxItem> items) {
		return items.stream().mapToLong(BoxItem::getWeight).sum();
	}

	static int houseBillCount(List<BoxItem> items) {
		Set<String> ids = new HashSet<>();
		for (BoxItem item : items) {
			String id = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			ids.add(id == null || id.isBlank() ? "<anonymous>" : id);
		}
		return ids.size();
	}

	static String houseBillIds(List<BoxItem> items) {
		Set<String> ids = new HashSet<>();
		for (BoxItem item : items) {
			String id = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			ids.add(safe(id == null || id.isBlank() ? "<anonymous>" : id));
		}
		return String.join(",", ids.stream().sorted().toList());
	}

	static double percent(long value, long maximum) {
		if (maximum <= 0L) return 0.0;
		return Math.round(value * 10_000.0 / maximum) / 100.0;
	}

	static double rounded(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	static String safe(String value) {
		if (value == null || value.isBlank()) return "unknown";
		return value.replaceAll("\\s+", "_");
	}
}
