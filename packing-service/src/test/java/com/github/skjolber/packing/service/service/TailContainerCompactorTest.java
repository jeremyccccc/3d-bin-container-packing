package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.Rotation;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class TailContainerCompactorTest {

	@Test
	void movesACompleteHouseBillIntoASupportedBlockInTheFrontContainer() {
		Container front = container("front", 1200, 1000, 1500);
		front.getStack().add(placement(box("platform", "FRONT", 1100, 900, 500), 0, 0, 0));
		front.getStack().add(placement(box("side-x", "FRONT", 100, 1000, 1500), 1100, 0, 0));
		front.getStack().add(placement(box("side-y", "FRONT", 1100, 100, 1500), 0, 900, 0));

		Container tail = container("tail", 1200, 1000, 1500);
		Box cargo = box("cargo", "316901", 500, 390, 250);
		for (int iz = 0; iz < 3; iz++) {
			for (int iy = 0; iy < 2; iy++) {
				for (int ix = 0; ix < 2; ix++) {
					tail.getStack().add(placement(cargo, ix * 500, iy * 390, iz * 250));
				}
			}
		}
		PackagerResult source = new PackagerResult(List.of(front, tail), 10L, false);

		PackagerResult compacted = new TailContainerCompactor(
				PlacementSupport.DEFAULT_POLICY, 32, 32).compact(source, 0L);

		assertThat(compacted.getContainers()).hasSize(1);
		assertThat(compacted.get(0).getStack().getPlacements()).hasSize(15);
		assertThat(compacted.get(0).getStack().getPlacements().stream()
				.filter(placement -> "316901".equals(houseBill(placement)))).hasSize(12);
		assertThat(PlacementSupport.validate(compacted, PlacementSupport.DEFAULT_POLICY).valid()).isTrue();
	}

	@Test
	void leavesTheOriginalResultUntouchedWhenNoCompleteBillFits() {
		Container front = container("front", 100, 100, 100);
		front.getStack().add(placement(box("front-cargo", "FRONT", 100, 100, 100), 0, 0, 0));
		Container tail = container("tail", 100, 100, 100);
		tail.getStack().add(placement(box("tail-cargo", "TAIL", 100, 100, 100), 0, 0, 0));
		PackagerResult source = new PackagerResult(List.of(front, tail), 10L, false);

		PackagerResult compacted = new TailContainerCompactor(
				PlacementSupport.DEFAULT_POLICY, 8, 8).compact(source, 0L);

		assertThat(compacted).isSameAs(source);
	}

	@Test
	void repacksTheRemainingTailWhenTheMovedBillWasSupportingIt() {
		Container front = container("front", 1200, 1000, 1500);
		front.getStack().add(placement(box("platform", "FRONT", 1100, 900, 500), 0, 0, 0));
		front.getStack().add(placement(box("side-x", "FRONT", 100, 1000, 1500), 1100, 0, 0));
		front.getStack().add(placement(box("side-y", "FRONT", 1100, 100, 1500), 0, 900, 0));

		Container tail = container("tail", 1200, 1000, 1500);
		Box base = box("base", "BASE", 500, 500, 200);
		Box top = box("top", "TOP", 400, 400, 100);
		tail.getStack().add(placement(base, 0, 0, 0));
		tail.getStack().add(placement(top, 0, 0, 200));
		PackagerResult source = new PackagerResult(List.of(front, tail), 10L, false);

		PackagerResult compacted = new TailContainerCompactor(
				PlacementSupport.DEFAULT_POLICY, 16, 16).compact(source, 0L);

		assertThat(compacted.getContainers()).hasSize(1);
		assertThat(compacted.get(0).getStack().getPlacements().stream()
				.filter(placement -> "BASE".equals(houseBill(placement)))).hasSize(1);
		assertThat(compacted.get(0).getStack().getPlacements().stream()
				.filter(placement -> "TOP".equals(houseBill(placement)))).hasSize(1);
		assertThat(PlacementSupport.validate(compacted, PlacementSupport.DEFAULT_POLICY).valid()).isTrue();
	}

	@Test
	void removesOnlyTheBlockingNeighborhoodWhenDirectInsertionFails() {
		Container front = container("front", 1000, 1000, 1000);
		front.getStack().add(placement(fixedBox("blocker", "FRONT-BLOCKER", 600, 1000, 1000), 200, 0, 0));

		Container tail = container("tail", 1000, 1000, 1000);
		tail.getStack().add(placement(box("moving", "TAIL", 400, 1000, 1000), 0, 0, 0));
		PackagerResult source = new PackagerResult(List.of(front, tail), 10L, false);

		PackagerResult compacted = new TailContainerCompactor(
				PlacementSupport.DEFAULT_POLICY, 16, 16).compact(source,
						System.currentTimeMillis() + 8_000L);

		assertThat(compacted.getContainers()).hasSize(1);
		assertThat(compacted.get(0).getStack().getPlacements()).hasSize(2);
		assertThat(compacted.get(0).getStack().getPlacements().stream()
				.filter(placement -> "TAIL".equals(houseBill(placement)))).hasSize(1);
		assertThat(PlacementSupport.validate(compacted, PlacementSupport.DEFAULT_POLICY).valid()).isTrue();
	}

	@Test
	void keepsBaselineAndDoesNotThrowWhenFixedPlacementsOverlap() {
		Container front = container("front", 1000, 1000, 1000);
		front.getStack().add(placement(box("first", "FRONT-1", 600, 600, 600), 0, 0, 0));
		front.getStack().add(placement(box("second", "FRONT-2", 600, 600, 600), 500, 0, 0));
		Container tail = container("tail", 1000, 1000, 1000);
		tail.getStack().add(placement(box("moving", "TAIL", 100, 100, 100), 0, 0, 0));
		PackagerResult source = new PackagerResult(List.of(front, tail), 10L, false);

		PackagerResult compacted = new TailContainerCompactor(
				PlacementSupport.DEFAULT_POLICY, 16, 16).compact(source,
						System.currentTimeMillis() + 2_000L);

		assertThat(compacted).isSameAs(source);
	}

	private static String houseBill(Placement placement) {
		return String.valueOf((Object) placement.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID));
	}

	private static Container container(String id, int dx, int dy, int dz) {
		return Container.newBuilder().withId(id).withSize(dx, dy, dz)
				.withEmptyWeight(0).withMaxLoadWeight(Integer.MAX_VALUE).build();
	}

	private static Box box(String id, String houseBill, int dx, int dy, int dz) {
		return Box.newBuilder().withId(id).withSize(dx, dy, dz).withWeight(1).withRotate3D()
				.withProperty(PackingMapper.PROP_HOUSE_BS_ID, houseBill).build();
	}

	private static Box fixedBox(String id, String houseBill, int dx, int dy, int dz) {
		return Box.newBuilder().withId(id).withSize(dx, dy, dz).withWeight(1)
				.withRotation(Rotation.newBuilder().withBottomAtZeroDegrees().build())
				.withProperty(PackingMapper.PROP_HOUSE_BS_ID, houseBill).build();
	}

	private static Placement placement(Box box, int x, int y, int z) {
		var value = box.getStackValues()[0];
		return new Placement(value, new DefaultPoint3D(x, y, z,
				x + value.getDx() - 1, y + value.getDy() - 1, z + value.getDz() - 1));
	}
}
