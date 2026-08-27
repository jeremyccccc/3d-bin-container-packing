package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class ExistingLayoutInsertionTest {

	@Test
	void insertsRotatedCargoIntoExistingSideGap() {
		Box existingBox = Box.newBuilder().withId("EXISTING").withSize(1550, 1500, 1230).withWeight(1).build();
		Container existing = Container.newBuilder()
				.withId("C1")
				.withDescription("40HQ-SWAPPED")
				.withSize(2352, 12032, 2698)
				.withMaxLoadWeight(26_600_000)
				.build();
		existing.getStack().add(new Placement(existingBox.getStackValues()[0],
				new DefaultPoint3D(0, 0, 0, 1549, 1499, 1229)));

		Box incoming = Box.newBuilder()
				.withId("FLEECE")
				.withSize(1800, 1400, 800)
				.withWeight(1)
				.withRotate3D()
				.build();
		PackagerResult result = new SingleContainerPackingOracle().insertIntoExisting(
				existing, new BoxItem(incoming, 1), System.currentTimeMillis() + 2_000);

		assertThat(result).isNotNull();
		assertThat(result.get(0).getStack().getPlacements()).hasSize(2);
		Placement inserted = result.get(0).getStack().getPlacements().get(1);
		assertThat(inserted.getBox().getId()).isEqualTo("FLEECE");
		assertThat(inserted.getAbsoluteEndX()).isLessThan(2352);
		assertThat(inserted.getAbsoluteEndY()).isLessThan(12032);
		assertThat(inserted.getAbsoluteEndZ()).isLessThan(2698);
		assertThat(List.of(result.get(0).getStack().getPlacements().get(0).intersects3D(inserted))).containsOnly(false);
	}
}
