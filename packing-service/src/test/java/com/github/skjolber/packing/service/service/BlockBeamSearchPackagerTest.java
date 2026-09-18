package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;

class BlockBeamSearchPackagerTest {

	@Test
	void packsRepeatedItemsAsFullySupportedBlocks() {
		Container container = container(200, 200, 200);
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(1).withRotate3D().build();

		PackagerResult result = new BlockBeamSearchPackager(12, 10)
				.pack(container, List.of(new BoxItem(box, 8)), 0L);

		assertThat(result).isNotNull();
		assertThat(result.isSuccess()).isTrue();
		assertThat(result.get(0).getStack().getPlacements()).hasSize(8);
		assertThat(PlacementSupport.validate(result, PlacementSupport.DEFAULT_POLICY).valid()).isTrue();
	}

	@Test
	void returnsNoResultWhenAllItemsCannotFit() {
		Container container = container(100, 100, 100);
		Box box = Box.newBuilder().withId("large").withSize(80, 80, 80)
				.withWeight(1).withRotate3D().build();

		PackagerResult result = new BlockBeamSearchPackager(8, 8)
				.pack(container, List.of(new BoxItem(box, 2)), 0L);

		assertThat(result).isNull();
	}

	@Test
	void resumesAPausedSearchInsteadOfStartingOver() {
		Container container = container(200, 200, 200);
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(1).withRotate3D().build();
		BlockBeamSearchPackager packager = new BlockBeamSearchPackager(12, 10);
		BlockBeamSearchPackager.SearchSession session = packager.newSession(
				container, List.of(new BoxItem(box, 8)), " test=resume");

		BlockBeamSearchPackager.SearchProgress paused = session.advance(System.currentTimeMillis());
		BlockBeamSearchPackager.SearchProgress completed = session.advance(0L);

		assertThat(paused.complete()).isFalse();
		assertThat(paused.exhausted()).isFalse();
		assertThat(completed.complete()).isTrue();
		assertThat(completed.result().get(0).getStack().getPlacements()).hasSize(8);
	}

	@Test
	void detectsWhenARemainingCriticalItemLosesItsLastUsableSpace() {
		Box critical = Box.newBuilder().withId("critical").withSize(1330, 940, 1350)
				.withWeight(1).build();
		List<BoxItem> items = List.of(new BoxItem(critical, 1));

		BlockBeamSearchPackager.Feasibility narrow = BlockBeamSearchPackager.feasibility(
				new int[] {1},
				List.of(new BlockBeamSearchPackager.Space(0, 0, 0, 4750, 852, 2698)),
				items);
		BlockBeamSearchPackager.Feasibility reserved = BlockBeamSearchPackager.feasibility(
				new int[] {1},
				List.of(new BlockBeamSearchPackager.Space(0, 0, 0, 4750, 1132, 2698)),
				items);

		assertThat(narrow.strandedTypes()).isEqualTo(1);
		assertThat(reserved.strandedTypes()).isZero();
		assertThat(reserved.minimumFitSpaces()).isEqualTo(1);
	}

	@Test
	void reservesPartOfTheBeamForStatesWithBetterCriticalItemOptions() {
		Container container = container(100, 100, 100);
		BlockBeamSearchPackager packager = new BlockBeamSearchPackager(4, 4);
		BlockBeamSearchPackager.State volumeLeader = state(900_000, 1, 0, 90);
		BlockBeamSearchPackager.State volumeRunnerUp = state(800_000, 1, 0, 80);
		BlockBeamSearchPackager.State criticalSpaceLeader = state(100_000, 4, 0, 500);
		BlockBeamSearchPackager.State openSpaceLeader = state(50_000, 2, 0, 50);
		BlockBeamSearchPackager.State ordinary = state(40_000, 1, 2, 10);

		List<BlockBeamSearchPackager.State> selected = packager.selectBeam(List.of(
				volumeLeader, volumeRunnerUp, criticalSpaceLeader, openSpaceLeader, ordinary), container);

		assertThat(selected).hasSize(4);
		assertThat(selected).contains(volumeLeader, volumeRunnerUp, criticalSpaceLeader);
	}

	private static BlockBeamSearchPackager.State state(long packedVolume, int fitSpaces,
			int scarceTypes, int clearance) {
		List<BlockBeamSearchPackager.Space> spaces = List.of(
				new BlockBeamSearchPackager.Space(0, 0, 0, 100, 100, 100));
		return new BlockBeamSearchPackager.State(new int[] {1}, spaces, List.of(),
				packedVolume, 0, 10, 10,
				new BlockBeamSearchPackager.Feasibility(0, scarceTypes, fitSpaces, clearance));
	}

	private static Container container(int dx, int dy, int dz) {
		return Container.newBuilder().withId("container").withSize(dx, dy, dz)
				.withMaxLoadWeight(10000).build();
	}
}
