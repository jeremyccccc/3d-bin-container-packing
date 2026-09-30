package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;

class BlockBeamBlockCatalogTest {

	@Test
	void generatesTheSameBoundedCatalogForTheSameInput() {
		Container container = container(1000, 1000, 1000);
		Box box = Box.newBuilder().withId("cube").withSize(100, 100, 100)
				.withWeight(2).build();
		List<BoxItem> items = List.of(new BoxItem(box, 120));

		List<BlockBeamSearchPackager.Block> first = BlockBeamBlockCatalog.generate(container, items);
		List<BlockBeamSearchPackager.Block> second = BlockBeamBlockCatalog.generate(container, items);

		assertThat(first).isNotEmpty().containsExactlyElementsOf(second);
		assertThat(first).allSatisfy(block -> {
			assertThat(block.units()).isBetween(1, 120);
			assertThat(block.nz()).isBetween(1, 5);
			assertThat(block.dx()).isLessThanOrEqualTo(container.getLoadDx());
			assertThat(block.dy()).isLessThanOrEqualTo(container.getLoadDy());
			assertThat(block.dz()).isLessThanOrEqualTo(container.getLoadDz());
		});
	}

	private static Container container(int dx, int dy, int dz) {
		return Container.newBuilder().withId("container").withSize(dx, dy, dz)
				.withMaxLoadWeight(10000).build();
	}
}
