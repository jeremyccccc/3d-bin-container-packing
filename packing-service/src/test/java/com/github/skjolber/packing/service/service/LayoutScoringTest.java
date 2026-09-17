package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class LayoutScoringTest {

	@Test
	void prefersAdjacentPlacementOfTheSameCargo() {
		Container container = container(500, 200, 100);
		Box box = box("same", 100, 100, 100);
		Placement placed = placement(box, 0, 0, 0);

		LayoutScoring.Score adjacent = score(box, 100, 0, container, List.of(placed), 100);
		LayoutScoring.Score isolated = score(box, 300, 0, container, List.of(placed), 100);

		assertThat(adjacent.sameOrientationContactPermille()).isGreaterThan(0L);
		assertThat(adjacent.compareTo(isolated)).isPositive();
	}

	@Test
	void prefersContinuingTheExistingOrientation() {
		Container container = container(600, 300, 100);
		Box box = Box.newBuilder().withId("same").withSize(200, 100, 100)
				.withWeight(1).withRotate3D().build();
		BoxStackValue horizontal = orientation(box, 200, 100, 100);
		BoxStackValue vertical = orientation(box, 100, 200, 100);
		Placement placed = placement(horizontal, 0, 0, 0);

		LayoutScoring.Score continued = score(horizontal, 200, 0, container, List.of(placed), 100);
		LayoutScoring.Score turned = score(vertical, 200, 0, container, List.of(placed), 100);

		assertThat(continued.sameOrientationContactPermille()).isGreaterThan(0L);
		assertThat(continued.rotatedContactPermille()).isZero();
		assertThat(turned.sameOrientationContactPermille()).isZero();
		assertThat(turned.rotatedContactPermille()).isGreaterThan(0L);
		assertThat(continued.compareTo(turned)).isPositive();
	}

	@Test
	void rotatedCargoDoesNotExtendAnExistingStraightRow() {
		Container container = container(400, 300, 100);
		Box box = Box.newBuilder().withId("same").withSize(200, 100, 100)
				.withWeight(1).withRotate3D().build();
		BoxStackValue horizontal = orientation(box, 200, 100, 100);
		BoxStackValue vertical = orientation(box, 100, 200, 100);
		Placement placed = placement(horizontal, 0, 0, 0);

		LayoutScoring.Score turned = score(vertical, 200, 0, container, List.of(placed), 100);

		assertThat(turned.completeRow()).isFalse();
		assertThat(turned.rowCoveragePermille()).isLessThan(1000L);
	}

	@Test
	void recognizesACompletedRow() {
		Container container = container(300, 100, 100);
		Box box = box("same", 100, 100, 100);
		List<Placement> row = List.of(placement(box, 0, 0, 0), placement(box, 100, 0, 0));

		LayoutScoring.Score score = score(box, 200, 0, container, row, 100);

		assertThat(score.completeRow()).isTrue();
		assertThat(score.rowCoveragePermille()).isEqualTo(1000L);
		assertThat(score.internalGap()).isZero();
	}

	@Test
	void detectsInternalRowGap() {
		Container container = container(400, 100, 100);
		Box box = box("same", 100, 100, 100);
		Placement placed = placement(box, 0, 0, 0);

		LayoutScoring.Score score = score(box, 200, 0, container, List.of(placed), 100);

		assertThat(score.internalGap()).isEqualTo(100L);
	}

	@Test
	void penalizesAWallAnchoredRemainderTooNarrowForRemainingCargo() {
		Container container = container(250, 100, 100);
		Box box = box("wide", 200, 100, 100);

		LayoutScoring.Score score = score(box, 0, 0, container, List.of(), 100);

		assertThat(score.narrowRemainder()).isEqualTo(50L);
	}

	@Test
	void detectsANarrowRemainderInsideTheCurrentFreePoint() {
		Container container = container(500, 200, 100);
		Box box = box("cargo", 100, 100, 100);
		BoxStackValue value = box.getStackValues()[0];

		LayoutScoring.Score score = LayoutScoring.evaluate(
				new DefaultPoint3D(100, 0, 0, 249, 199, 99), value, List.of(), container, 100);

		assertThat(score.narrowRemainder()).isEqualTo(50L);
	}

	private static LayoutScoring.Score score(Box box, int x, int y, Container container,
			List<Placement> placements, int minimumRemainingEdge) {
		return score(box.getStackValues()[0], x, y, container, placements, minimumRemainingEdge);
	}

	private static LayoutScoring.Score score(BoxStackValue value, int x, int y, Container container,
			List<Placement> placements, int minimumRemainingEdge) {
		return LayoutScoring.evaluate(
				new DefaultPoint3D(x, y, 0, container.getLoadDx() - 1, container.getLoadDy() - 1,
						container.getLoadDz() - 1),
				value, placements, container, minimumRemainingEdge);
	}

	private static Placement placement(Box box, int x, int y, int z) {
		return placement(box.getStackValues()[0], x, y, z);
	}

	private static Placement placement(BoxStackValue value, int x, int y, int z) {
		return new Placement(value, new DefaultPoint3D(x, y, z,
				x + value.getDx() - 1, y + value.getDy() - 1, z + value.getDz() - 1));
	}

	private static BoxStackValue orientation(Box box, int dx, int dy, int dz) {
		for (BoxStackValue value : box.getStackValues()) {
			if (value.getDx() == dx && value.getDy() == dy && value.getDz() == dz) {
				return value;
			}
		}
		throw new IllegalArgumentException("Missing orientation " + dx + "x" + dy + "x" + dz);
	}

	private static Box box(String id, int dx, int dy, int dz) {
		return Box.newBuilder().withId(id).withSize(dx, dy, dz).withWeight(1).build();
	}

	private static Container container(int dx, int dy, int dz) {
		return Container.newBuilder().withId("container").withSize(dx, dy, dz)
				.withMaxLoadWeight(10_000).build();
	}
}
