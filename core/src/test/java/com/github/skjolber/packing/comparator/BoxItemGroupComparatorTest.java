package com.github.skjolber.packing.comparator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.BoxItemGroup;

class BoxItemGroupComparatorTest {

	@Test
	void volumeComparatorPrefersLargerGroup() {
		BoxItemGroup small = group("small", 1, 1, 1, 1);
		BoxItemGroup large = group("large", 2, 2, 2, 1);

		VolumeThenWeightBoxItemGroupComparator comparator = new VolumeThenWeightBoxItemGroupComparator();
		assertTrue(comparator.compare(large, small) < 0);
		assertTrue(comparator.compare(small, large) > 0);
	}

	@Test
	void areaComparatorPrefersLargerAreaGroup() {
		BoxItemGroup small = group("small", 1, 1, 1, 1);
		BoxItemGroup large = group("large", 3, 2, 1, 1);

		LargestAreaBoxItemGroupComparator comparator = new LargestAreaBoxItemGroupComparator();
		assertTrue(comparator.compare(large, small) < 0);
		assertTrue(comparator.compare(small, large) > 0);
	}

	private static BoxItemGroup group(String id, int dx, int dy, int dz, int weight) {
		Box box = Box.newBuilder()
				.withSize(dx, dy, dz)
				.withWeight(weight)
				.build();
		return new BoxItemGroup(id, List.of(new BoxItem(box, 1)));
	}
}
