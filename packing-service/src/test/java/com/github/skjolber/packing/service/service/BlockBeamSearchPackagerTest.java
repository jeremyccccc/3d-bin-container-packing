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

	private static Container container(int dx, int dy, int dz) {
		return Container.newBuilder().withId("container").withSize(dx, dy, dz)
				.withMaxLoadWeight(10000).build();
	}
}
