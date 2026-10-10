package com.github.skjolber.packing.service.service.assignment;

import java.util.List;

import com.github.skjolber.packing.api.BoxItem;

record WholeOrderGroup(String id, List<BoxItem> items, long volume, long weight,
		long largestBox, String stableKey) {
}
