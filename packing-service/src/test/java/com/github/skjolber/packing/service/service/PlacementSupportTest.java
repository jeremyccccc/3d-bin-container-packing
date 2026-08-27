package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class PlacementSupportTest {

	@Test
	void allowsCargoOnContainerFloor() {
		Placement candidate = placement("CANDIDATE", 0, 0, 0, 100, 100, 100);

		assertThat(PlacementSupport.isFullySupported(candidate, List.of(candidate))).isTrue();
	}

	@Test
	void allowsCargoFullySupportedByMultipleBoxes() {
		Placement left = placement("LEFT", 0, 0, 0, 50, 100, 100);
		Placement right = placement("RIGHT", 50, 0, 0, 50, 100, 100);
		Placement candidate = placement("CANDIDATE", 0, 0, 100, 100, 100, 100);

		assertThat(PlacementSupport.isFullySupported(candidate, List.of(left, right, candidate))).isTrue();
	}

	@Test
	void rejectsCargoSupportedOnlyAlongOneEdge() {
		Placement edge = placement("EDGE", 0, 0, 0, 10, 100, 100);
		Placement candidate = placement("CANDIDATE", 0, 0, 100, 100, 100, 100);

		assertThat(PlacementSupport.isFullySupported(candidate, List.of(edge, candidate))).isFalse();
	}

	@Test
	void rejectsCargoWithGapBetweenSupports() {
		Placement left = placement("LEFT", 0, 0, 0, 40, 100, 100);
		Placement right = placement("RIGHT", 60, 0, 0, 40, 100, 100);
		Placement candidate = placement("CANDIDATE", 0, 0, 100, 100, 100, 100);

		assertThat(PlacementSupport.isFullySupported(candidate, List.of(left, right, candidate))).isFalse();
	}

	private static Placement placement(String id, int x, int y, int z, int dx, int dy, int dz) {
		Box box = Box.newBuilder().withId(id).withSize(dx, dy, dz).withWeight(1).build();
		return new Placement(box.getStackValues()[0],
				new DefaultPoint3D(x, y, z, x + dx - 1, y + dy - 1, z + dz - 1));
	}
}
