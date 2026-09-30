package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class BlockBeamFixedPlacementIndexTest {

	@Test
	void usesHalfOpenCollisionBounds() {
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(1).build();
		Placement fixed = new Placement(box.getStackValues()[0],
				new DefaultPoint3D(0, 0, 0, 99, 99, 99));
		BlockBeamSearchPackager.Block block = new BlockBeamSearchPackager.Block(
				0, box, box.getStackValues()[0], 1, 1, 1,
				1, 100, 100, 100, box.getWeight(), box.getVolume());
		BlockBeamFixedPlacementIndex index = new BlockBeamFixedPlacementIndex(List.of(fixed));

		assertThat(index.intersects(50, 50, 50, block)).isTrue();
		assertThat(index.intersects(100, 0, 0, block)).isFalse();
		assertThat(index.intersects(0, 0, 100, block)).isFalse();
	}
}
