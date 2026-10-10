package com.github.skjolber.packing.service.service.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;
import com.github.skjolber.packing.service.service.compaction.SpaceGuidedRepairPlanner.Candidate;
import com.github.skjolber.packing.service.service.compaction.SpaceGuidedRepairPlanner.Neighborhood;
import com.github.skjolber.packing.service.service.compaction.SpaceGuidedRepairPlanner.RequiredShape;
import com.github.skjolber.packing.service.service.compaction.SpaceGuidedRepairPlanner.Region;
import com.github.skjolber.packing.service.service.mapping.PackingMapper;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

class TailCompactionComponentsTest {

	@Test
	void supportGroupsCompleteHouseBillsAndRemovesOnlyTheSelectedBill() {
		Container tail = container("tail", 100, 100, 100);
		tail.getStack().add(placement(box("a-1", "A", 20, 20, 20), 0, 0, 0));
		tail.getStack().add(placement(box("a-2", "A", 10, 10, 10), 20, 0, 0));
		tail.getStack().add(placement(box("b-1", "B", 30, 30, 30), 30, 0, 0));

		List<TailCompactionSupport.HouseBillLoad> bills = TailCompactionSupport.houseBills(tail);
		Container remaining = TailCompactionSupport.withoutHouseBill(tail, "A");

		assertThat(bills).extracting(TailCompactionSupport.HouseBillLoad::houseBsId)
				.containsExactly("A", "B");
		assertThat(bills.get(0).units()).isEqualTo(2);
		assertThat(remaining.getStack().getPlacements()).extracting(placement -> placement.getBox().getId())
				.containsExactly("b-1");
	}

	@Test
	void plannerFindsBlockingRegionAndItsSupportClosure() {
		Container target = container("target", 100, 100, 100);
		Placement base = placement(box("base", "FRONT", 60, 100, 40), 20, 0, 0);
		Placement top = placement(box("top", "FRONT", 40, 80, 20), 30, 10, 40);
		target.getStack().add(base);
		target.getStack().add(top);
		SpaceGuidedRepairPlanner planner = new SpaceGuidedRepairPlanner(
				PlacementSupport.DEFAULT_POLICY, 8, 8);

		List<Candidate> candidates = planner.candidates(
				target, List.of(new RequiredShape(50, 100, 50)));
		Neighborhood neighborhood = planner.neighborhood(
				target, new Region(20, 0, 0, 60, 100, 40));

		assertThat(candidates).isNotEmpty();
		assertThat(neighborhood.removed()).contains(base, top);
		assertThat(neighborhood.fixed()).isEmpty();
	}

	@Test
	void validatorRejectsAHouseBillSplitAcrossContainers() {
		Container source = container("source", 100, 100, 100);
		Box first = box("first", "SAME", 20, 20, 20);
		Box second = box("second", "SAME", 20, 20, 20);
		source.getStack().add(placement(first, 0, 0, 0));
		source.getStack().add(placement(second, 20, 0, 0));
		Container left = container("left", 100, 100, 100);
		left.getStack().add(placement(first, 0, 0, 0));
		Container right = container("right", 100, 100, 100);
		right.getStack().add(placement(second, 0, 0, 0));
		Container equivalent = container("equivalent", 100, 100, 100);
		equivalent.getStack().add(placement(first, 0, 0, 0));
		equivalent.getStack().add(placement(second, 20, 0, 0));
		TailCompactionValidator validator = new TailCompactionValidator(
				PlacementSupport.DEFAULT_POLICY);

		assertThat(validator.validFinalResult(List.of(source), List.of(left, right))).isFalse();
		assertThat(validator.validFinalResult(List.of(source), List.of(equivalent))).isTrue();
		assertThat(validator.validGeometry(
				new PackagerResult(List.of(source), 0L, false), "test", "SAME", source)).isTrue();
	}

	private static Container container(String id, int dx, int dy, int dz) {
		return Container.newBuilder().withId(id).withSize(dx, dy, dz)
				.withEmptyWeight(0).withMaxLoadWeight(Integer.MAX_VALUE).build();
	}

	private static Box box(String id, String houseBill, int dx, int dy, int dz) {
		return Box.newBuilder().withId(id).withSize(dx, dy, dz).withWeight(1).withRotate3D()
				.withProperty(PackingMapper.PROP_HOUSE_BS_ID, houseBill).build();
	}

	private static Placement placement(Box box, int x, int y, int z) {
		var value = box.getStackValues()[0];
		return new Placement(value, new DefaultPoint3D(x, y, z,
				x + value.getDx() - 1, y + value.getDy() - 1, z + value.getDz() - 1));
	}
}
