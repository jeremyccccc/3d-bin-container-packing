package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class BottomRuleSupportTest {

	@Test
	void allowsBottomCargoOnContainerFloor() {
		Box bottom = box("H1", 1);

		assertThat(BottomRuleSupport.isAllowed(point(0), stackValue(bottom), List.of())).isTrue();
	}

	@Test
	void allowsBottomCargoOnCargoFromSameHouseBill() {
		Box bottom = box("H1", 1);
		Placement support = placement(box("H1", 1), 0);

		assertThat(BottomRuleSupport.isAllowed(point(100), stackValue(bottom), List.of(support))).isTrue();
	}

	@Test
	void rejectsBottomCargoOnCargoFromAnotherHouseBill() {
		Box bottom = box("H1", 1);
		Placement support = placement(box("H2", 0), 0);

		assertThat(BottomRuleSupport.isAllowed(point(100), stackValue(bottom), List.of(support))).isFalse();
	}

	@Test
	void rejectsBottomCargoWithMixedHouseBillSupport() {
		Box bottom = box("H1", 1);
		Placement sameHouse = placement(box("H1", 1), 0);
		Placement otherHouse = new Placement(stackValue(box("H2", 0)),
				new DefaultPoint3D(50, 0, 0, 149, 99, 99));

		assertThat(BottomRuleSupport.isAllowed(point(100), stackValue(bottom), List.of(sameHouse, otherHouse))).isFalse();
	}

	@Test
	void ordinaryCargoIsNotRestrictedByBottomRule() {
		Box ordinary = box("H1", 0);
		Placement otherHouse = placement(box("H2", 0), 0);

		assertThat(BottomRuleSupport.isAllowed(point(100), stackValue(ordinary), List.of(otherHouse))).isTrue();
	}

	private static Box box(String houseBsId, int heightPosition) {
		return Box.newBuilder()
				.withId(houseBsId)
				.withSize(100, 100, 100)
				.withWeight(1)
				.withRotate3D()
				.withProperty(PackingMapper.PROP_HOUSE_BS_ID, houseBsId)
				.withProperty(PackingMapper.PROP_HEIGHT_POSITION, heightPosition)
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
