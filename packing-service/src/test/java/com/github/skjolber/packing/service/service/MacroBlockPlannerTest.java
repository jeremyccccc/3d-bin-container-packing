package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;

class MacroBlockPlannerTest {

	@Test
	void adaptiveCuboidsMinimizeBlockAndRemainderObjectCount() {
		Container container = Container.newBuilder().withSize(1200, 240, 260).withEmptyWeight(0)
				.withMaxLoadWeight(100000).build();
		Box a = Box.newBuilder().withId("a").withSize(100, 20, 20).withWeight(1)
				.withRotate3D().build();
		Box b = Box.newBuilder().withId("b").withSize(80, 30, 30).withWeight(1)
				.withRotate3D().build();

		MacroBlockPlanner.Plan plan = MacroBlockPlanner.adaptiveCuboids(
				List.of(new BoxItem(a, 33), new BoxItem(b, 50)), container);

		assertThat(plan.blocks()).hasSize(2);
		assertThat(plan.boxItems()).hasSize(3);
		assertThat(plan.boxItems().get(0).getCount()).isEqualTo(2); // two 16-unit blocks
		assertThat(plan.boxItems().get(1).getCount()).isEqualTo(1); // one loose unit
		assertThat(plan.boxItems().get(2).getCount()).isEqualTo(5); // five exact 10-unit blocks
	}

	@Test
	void buildsCompleteFixedOrientationRowsAndKeepsRemainderAsUnits() {
		Box box = box("cargo", 100, 50, 40);
		Container container = container(1000, 200, 300);

		MacroBlockPlanner.Plan plan = MacroBlockPlanner.rowsAcrossY(
				List.of(new BoxItem(box, 10)), container);

		assertThat(plan.hasMacros()).isTrue();
		assertThat(plan.boxItems()).hasSize(2);
		BoxItem macro = plan.boxItems().get(0);
		assertThat(macro.getCount()).isEqualTo(2);
		assertThat(macro.getBox().getStackValues()).hasSize(2);
		assertThat(macro.getBox().getStackValues()[0].getDx()).isEqualTo(100);
		assertThat(macro.getBox().getStackValues()[0].getDy()).isEqualTo(200);
		assertThat(macro.getBox().getStackValues()[0].getDz()).isEqualTo(40);
		assertThat(plan.boxItems().get(1).getCount()).isEqualTo(2);
	}

	@Test
	void expandsRowsBackToOriginalCargoPlacements() {
		Box box = box("cargo", 100, 50, 40);
		Container container = container(1000, 200, 300);
		MacroBlockPlanner.Plan plan = MacroBlockPlanner.rowsAcrossY(
				List.of(new BoxItem(box, 4)), container);
		Box macro = plan.boxItems().get(0).getBox();
		Container packed = container.clone();
		packed.getStack().add(new Placement(macro.getStackValues()[0], -1, 20, 0, 0));

		PackagerResult result = plan.expand(new PackagerResult(List.of(packed), 0L, false));

		assertThat(result.get(0).getStack().getPlacements()).hasSize(4);
		assertThat(result.get(0).getStack().getPlacements())
				.extracting(Placement::getAbsoluteY)
				.containsExactly(0, 50, 100, 150);
		assertThat(result.get(0).getStack().getPlacements())
				.allMatch(placement -> placement.getBox().getId().equals("cargo"));
		assertThat(PlacementSupport.validate(result, PlacementSupport.DEFAULT_POLICY).valid()).isTrue();
	}

	@Test
	void limitsRowsToTheRequestedFlexibleBlockSize() {
		Box box = box("cargo", 100, 50, 40);
		Container container = container(1000, 500, 300);

		MacroBlockPlanner.Plan plan = MacroBlockPlanner.rowsAcrossY(
				List.of(new BoxItem(box, 10)), container, 2);

		BoxItem macro = plan.boxItems().get(0);
		assertThat(macro.getCount()).isEqualTo(5);
		assertThat(macro.getBox().getStackValues()[0].getDy()).isEqualTo(200);
	}

	@Test
	void buildsAndExpandsAFlatTwoByTwoGrid() {
		Box box = box("cargo", 100, 100, 40);
		Container container = container(1000, 200, 300);
		MacroBlockPlanner.Plan plan = MacroBlockPlanner.grid(
				List.of(new BoxItem(box, 4)), container, 2, 2);
		Box macro = plan.boxItems().get(0).getBox();
		Container packed = container.clone();
		packed.getStack().add(new Placement(macro.getStackValues()[0], -1, 0, 0, 0));

		PackagerResult result = plan.expand(new PackagerResult(List.of(packed), 0L, false));

		assertThat(macro.getStackValues()[0].getDx()).isEqualTo(200);
		assertThat(macro.getStackValues()[0].getDy()).isEqualTo(200);
		assertThat(result.get(0).getStack().getPlacements())
				.extracting(placement -> placement.getAbsoluteX() + ":" + placement.getAbsoluteY())
				.containsExactly("0:0", "0:100", "100:0", "100:100");
	}

	@Test
	void expandsAlignedLayersWithFullInternalSupport() {
		Box box = box("cargo", 100, 100, 40);
		Container container = container(1000, 300, 300);
		MacroBlockPlanner.Plan plan = MacroBlockPlanner.cuboid(
				List.of(new BoxItem(box, 6)), container, 1, 2, 3, Integer.MAX_VALUE);
		Box macro = plan.boxItems().get(0).getBox();
		Container packed = container.clone();
		packed.getStack().add(new Placement(macro.getStackValues()[0], -1, 0, 0, 0));

		PackagerResult result = plan.expand(new PackagerResult(List.of(packed), 0L, false));

		assertThat(macro.getStackValues()[0].getDz()).isEqualTo(120);
		assertThat(result.get(0).getStack().getPlacements()).hasSize(6);
		assertThat(result.get(0).getStack().getPlacements())
				.extracting(Placement::getAbsoluteZ)
				.containsExactly(0, 40, 80, 0, 40, 80);
		assertThat(PlacementSupport.validate(result, PlacementSupport.DEFAULT_POLICY).valid()).isTrue();
	}

	private static Box box(String id, int dx, int dy, int dz) {
		return Box.newBuilder().withId(id).withSize(dx, dy, dz).withWeight(10)
				.withRotate3D().withProperty(PackingMapper.PROP_CARGO_ID, id).build();
	}

	private static Container container(int dx, int dy, int dz) {
		return Container.newBuilder().withId("container").withSize(dx, dy, dz)
				.withMaxLoadWeight(100_000).build();
	}
}
