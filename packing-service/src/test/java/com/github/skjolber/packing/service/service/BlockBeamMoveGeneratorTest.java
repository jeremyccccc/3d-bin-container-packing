package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;

class BlockBeamMoveGeneratorTest {

	@Test
	void keepsTheBestMoveForEveryItemTypeOutsideTheGlobalBranchingBudget() {
		Container container = Container.newBuilder().withId("container").withSize(300, 300, 300)
				.withMaxLoadWeight(10000).build();
		Box large = Box.newBuilder().withId("large").withSize(200, 200, 200).withWeight(1).build();
		Box small = Box.newBuilder().withId("small").withSize(100, 100, 100).withWeight(1).build();
		List<BoxItem> items = List.of(new BoxItem(large, 1), new BoxItem(small, 1));
		List<BlockBeamSearchPackager.Block> catalog = BlockBeamBlockCatalog.generate(container, items);
		List<BlockBeamSearchPackager.Space> spaces = List.of(
				new BlockBeamSearchPackager.Space(0, 0, 0, 300, 300, 300));
		BlockBeamSearchPackager.State state = new BlockBeamSearchPackager.State(
				new int[] {1, 1}, spaces, List.of(), 0L, 0, 0, 0,
				BlockBeamSearchPackager.feasibility(new int[] {1, 1}, spaces, items));
		BlockBeamMoveGenerator generator = new BlockBeamMoveGenerator(
				1, PlacementSupport.DEFAULT_POLICY, BlockBeamSearchPackager.SearchProfile.BALANCED);

		List<BlockBeamSearchPackager.Move> moves = generator.moves(
				state, catalog, container, List.of(), new BlockBeamFixedPlacementIndex(List.of()));

		assertThat(moves).extracting(move -> move.block().itemIndex()).containsExactly(0, 1);
		assertThat(moves.get(0).rank()).isGreaterThanOrEqualTo(moves.get(1).rank());
	}
}
