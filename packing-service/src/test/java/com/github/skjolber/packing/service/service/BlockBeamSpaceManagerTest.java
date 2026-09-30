package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.Container;

class BlockBeamSpaceManagerTest {

	@Test
	void splitsTheContainingSpaceWithoutKeepingZeroSizedOrDuplicateSpaces() {
		Container container = Container.newBuilder().withId("container").withSize(200, 200, 200)
				.withMaxLoadWeight(10000).build();
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(1).build();
		BlockBeamSearchPackager.Block block = new BlockBeamSearchPackager.Block(
				0, box, box.getStackValues()[0], 1, 1, 1,
				1, 100, 100, 100, box.getWeight(), box.getVolume());
		List<BlockBeamSearchPackager.Space> initial = BlockBeamSpaceManager.initialSpaces(
				container, List.of());

		List<BlockBeamSearchPackager.Space> remaining = BlockBeamSpaceManager.updateSpaces(
				initial, 0, 0, 0, block, container);

		assertThat(initial).containsExactly(
				new BlockBeamSearchPackager.Space(0, 0, 0, 200, 200, 200));
		assertThat(remaining).containsExactly(
				new BlockBeamSearchPackager.Space(100, 0, 0, 100, 200, 200),
				new BlockBeamSearchPackager.Space(0, 100, 0, 200, 100, 200),
				new BlockBeamSearchPackager.Space(0, 0, 100, 100, 100, 100));
	}
}
