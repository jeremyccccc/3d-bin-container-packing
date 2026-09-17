package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class PlacementSupportTest {

	private static final PlacementSupport.Policy POLICY = PlacementSupport.DEFAULT_POLICY;

	@Test
	void acceptsGroundPlacement() {
		Placement placement = placement("candidate", 0, 0, 0, 100, 100, 100);

		assertThat(PlacementSupport.validate(placement, List.of(placement), POLICY).valid()).isTrue();
	}

	@Test
	void acceptsNinetyPercentSupportWithCenterAndSmallOverhang() {
		Placement support = placement("support", 5, 0, 0, 95, 100, 100);
		Placement candidate = placement("candidate", 0, 0, 100, 100, 100, 100);

		PlacementSupport.Validation validation = PlacementSupport.validate(candidate, List.of(support, candidate), POLICY);

		assertThat(validation.valid()).isTrue();
	}

	@Test
	void rejectsInsufficientArea() {
		Placement support = placement("support", 20, 0, 0, 80, 100, 100);
		Placement candidate = placement("candidate", 0, 0, 100, 100, 100, 100);

		PlacementSupport.Validation validation = PlacementSupport.validate(candidate, List.of(support, candidate), POLICY);

		assertThat(validation.valid()).isFalse();
		assertThat(validation.reason()).isEqualTo("support-area");
	}

	@Test
	void rejectsBridgeWithoutCenterSupport() {
		Placement left = placement("left", 0, 0, 0, 45, 100, 100);
		Placement right = placement("right", 55, 0, 0, 45, 100, 100);
		Placement candidate = placement("candidate", 0, 0, 100, 100, 100, 100);

		PlacementSupport.Validation validation = PlacementSupport.validate(candidate, List.of(left, right, candidate), POLICY);

		assertThat(validation.valid()).isFalse();
		assertThat(validation.reason()).isEqualTo("center-support");
	}

	@Test
	void rejectsExcessiveOneSideOverhangEvenWhenAreaPassesCustomThreshold() {
		Placement support = placement("support", 10, 0, 0, 90, 100, 100);
		Placement candidate = placement("candidate", 0, 0, 100, 100, 100, 100);
		PlacementSupport.Policy policy = new PlacementSupport.Policy(0.85, true, 20, 0.05);

		PlacementSupport.Validation validation = PlacementSupport.validate(candidate, List.of(support, candidate), policy);

		assertThat(validation.valid()).isFalse();
		assertThat(validation.reason()).isEqualTo("maximum-overhang");
	}

	@Test
	void combinesAdjacentSupportsWithoutDoubleCounting() {
		Placement left = placement("left", 0, 0, 0, 50, 100, 100);
		Placement right = placement("right", 50, 0, 0, 50, 100, 100);
		Placement candidate = placement("candidate", 0, 0, 100, 100, 100, 100);

		assertThat(PlacementSupport.validate(candidate, List.of(left, right, candidate), POLICY).valid()).isTrue();
	}

	private static Placement placement(String id, int x, int y, int z, int dx, int dy, int dz) {
		Box box = Box.newBuilder().withId(id).withSize(dx, dy, dz).withWeight(1).build();
		return new Placement(box.getStackValues()[0],
				new DefaultPoint3D(x, y, z, x + dx - 1, y + dy - 1, z + dz - 1));
	}
}
