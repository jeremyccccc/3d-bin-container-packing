package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class NoPressRuleSupportTest {

	@Test
	void allowsNoPressCargoOnOrdinaryCargo() {
		Box candidate = box("H1", true);
		Placement support = placement(box("H2", false), 0);

		assertThat(NoPressRuleSupport.isAllowed(point(100), stackValue(candidate), List.of(support))).isTrue();
	}

	@Test
	void rejectsOrdinaryCargoOnNoPressCargoFromSameHouseBill() {
		Box candidate = box("H1", false);
		Placement support = placement(box("H1", true), 0);

		assertThat(NoPressRuleSupport.isAllowed(point(100), stackValue(candidate), List.of(support))).isFalse();
	}

	@Test
	void rejectsOtherHouseBillCargoOnNoPressCargo() {
		Box candidate = box("H2", true);
		Placement support = placement(box("H1", true), 0);

		assertThat(NoPressRuleSupport.isAllowed(point(100), stackValue(candidate), List.of(support))).isFalse();
	}

	@Test
	void allowsSameHouseBillNoPressCargoToStackUpToThreeLayers() {
		Box candidate = box("H1", true);
		Placement first = placement(box("H1", true), 0);
		Placement second = placement(box("H1", true), 100);

		assertThat(NoPressRuleSupport.isAllowed(point(200), stackValue(candidate), List.of(first, second))).isTrue();
	}

	@Test
	void rejectsSameHouseBillNoPressCargoAtFourthLayer() {
		Box candidate = box("H1", true);
		Placement first = placement(box("H1", true), 0);
		Placement second = placement(box("H1", true), 100);
		Placement third = placement(box("H1", true), 200);

		assertThat(NoPressRuleSupport.isAllowed(point(300), stackValue(candidate), List.of(first, second, third))).isFalse();
	}

	private static Box box(String houseBsId, boolean noPress) {
		return Box.newBuilder()
				.withId(houseBsId + "-" + noPress)
				.withSize(100, 100, 100)
				.withWeight(1)
				.withRotate3D()
				.withProperty(PackingMapper.PROP_HOUSE_BS_ID, houseBsId)
				.withProperty(PackingMapper.PROP_NO_PRESS, noPress)
				.build();
	}

	private static BoxStackValue stackValue(Box box) {
		return box.getStackValues()[0];
	}

	private static Placement placement(Box box, int z) {
		return new Placement(stackValue(box), new DefaultPoint3D(0, 0, z, 99, 99, z + 99));
	}

	private static DefaultPoint3D point(int z) {
		return new DefaultPoint3D(0, 0, z, 99, 99, z + 99);
	}
}
