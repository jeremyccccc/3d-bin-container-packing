package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class PackingPlanComparatorTest {

	@Test
	void prefersFewerContainersBeforeUtilization() {
		PackagerResult oneContainer = result(container("C1", 100));
		PackagerResult twoContainers = result(container("C1", 900), container("C2", 100));

		assertThat(PackingPlanComparator.INSTANCE.compare(oneContainer, twoContainers)).isPositive();
	}

	@Test
	void comparesContainerVolumesLexicographically() {
		PackagerResult frontLoaded = result(container("C1", 600), container("C2", 400));
		PackagerResult balanced = result(container("C1", 500), container("C2", 500));

		assertThat(PackingPlanComparator.INSTANCE.compare(frontLoaded, balanced)).isPositive();
	}

	@Test
	void usesPlacementCountAfterVolume() {
		PackagerResult moreItems = result(container("C1", 300, 300), container("C2", 100));
		PackagerResult fewerItems = result(container("C1", 600), container("C2", 100));

		assertThat(PackingPlanComparator.INSTANCE.compare(moreItems, fewerItems)).isPositive();
	}

	private static PackagerResult result(Container... containers) {
		return new PackagerResult(List.of(containers), 0, false);
	}

	private static Container container(String id, int... volumes) {
		Container container = Container.newBuilder()
				.withId(id)
				.withSize(10_000, 10_000, 10_000)
				.withMaxLoadWeight(10_000)
				.build();
		int x = 0;
		for (int volume : volumes) {
			Box box = Box.newBuilder().withSize(volume, 1, 1).withWeight(1).build();
			container.getStack().add(new Placement(box.getStackValues()[0],
					new DefaultPoint3D(x, 0, 0, x + volume - 1, 0, 0)));
			x += volume;
		}
		return container;
	}
}
