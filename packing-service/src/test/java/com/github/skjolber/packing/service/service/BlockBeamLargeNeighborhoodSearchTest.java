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

class BlockBeamLargeNeighborhoodSearchTest {

	@Test
	void fillsAnExistingGapWithoutMovingPackedCargo() {
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(1).withRotate3D().build();
		Container container = Container.newBuilder().withId("container")
				.withSize(300, 100, 100).withMaxLoadWeight(100).build();
		Placement front = placement(box, 0, 0, 0);
		Placement rear = placement(box, 200, 0, 0);
		container.getStack().add(front);
		container.getStack().add(rear);
		BlockBeamSearchPackager.PartialSolution partial =
				new BlockBeamSearchPackager.PartialSolution(container,
						List.of(new BoxItem(box, 1)), box.getVolume() * 2L,
						box.getVolume() * 3L, 2, 3);

		PackagerResult filled = new ExtremePointGapFiller(
				PlacementSupport.DEFAULT_POLICY).fill(partial, 0L, " test=gap");

		assertThat(filled).isNotNull();
		assertThat(filled.get(0).getStack().getPlacements()).hasSize(3);
		assertThat(filled.get(0).getStack().getPlacements()).contains(front, rear);
	}

	@Test
	void gapFillerStopsWhenTheDeadlineHasExpired() {
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(1).withRotate3D().build();
		Container container = Container.newBuilder().withId("container")
				.withSize(300, 100, 100).withMaxLoadWeight(100).build();
		BlockBeamSearchPackager.PartialSolution partial =
				new BlockBeamSearchPackager.PartialSolution(container,
						List.of(new BoxItem(box, 1)), 0L, box.getVolume(), 0, 1);

		PackagerResult filled = new ExtremePointGapFiller(
				PlacementSupport.DEFAULT_POLICY).fill(
						partial, System.currentTimeMillis() - 1L, " test=expired-gap");

		assertThat(filled).isNull();
		assertThat(container.getStack().getPlacements()).isEmpty();
	}

	@Test
	void repairsATailRegionWithoutMovingTheFrontCargo() {
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(1).withRotate3D().build();
		Container container = Container.newBuilder().withId("container")
				.withSize(300, 100, 100).withMaxLoadWeight(100).build();
		Placement front = placement(box, 0, 0, 0);
		container.getStack().add(front);
		container.getStack().add(placement(box, 100, 0, 0));
		BlockBeamSearchPackager.PartialSolution partial =
				new BlockBeamSearchPackager.PartialSolution(container,
						List.of(new BoxItem(box, 1)), box.getVolume() * 2L,
						box.getVolume() * 3L, 2, 3);

		PackagerResult repaired = new BlockBeamLargeNeighborhoodSearch(
				PlacementSupport.DEFAULT_POLICY).repair(partial, 0L, " test=lns");

		assertThat(repaired).isNotNull();
		assertThat(repaired.get(0).getStack().getPlacements()).hasSize(3);
		assertThat(repaired.get(0).getStack().getPlacements()).contains(front);
		assertThat(PlacementSupport.validate(repaired, PlacementSupport.DEFAULT_POLICY).valid()).isTrue();
	}

	private static Placement placement(Box box, int x, int y, int z) {
		var value = box.getStackValues()[0];
		return new Placement(value, new DefaultPoint3D(x, y, z,
				x + value.getDx() - 1, y + value.getDy() - 1, z + value.getDz() - 1));
	}
}
