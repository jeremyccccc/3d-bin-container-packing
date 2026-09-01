package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxStackValue;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.api.Placement;
import com.github.skjolber.packing.api.Stack;
import com.github.skjolber.packing.ep.points3d.DefaultPoint3D;

class DoorSideRuleSupportTest {

	@Test
	void calculatesDistanceToXMaxForNormalContainer() {
		Container container = container(1000, 200, 100);
		BoxStackValue stackValue = stackValue(box("H1", true, 100, 50, 50));

		long distance = DoorSideRuleSupport.candidateDistance(container,
				new DefaultPoint3D(800, 0, 0, 899, 49, 49), stackValue);

		assertThat(distance).isEqualTo(199);
	}

	@Test
	void calculatesDistanceToYMaxForSwappedContainer() {
		Container container = container(200, 1000, 100);
		BoxStackValue stackValue = stackValue(box("H1", true, 100, 50, 50));

		long distance = DoorSideRuleSupport.candidateDistance(container,
				new DefaultPoint3D(0, 800, 0, 99, 849, 49), stackValue);

		assertThat(distance).isEqualTo(199);
	}

	@Test
	void scoresHouseBillByWholeTicketDoorSideDistance() {
		Stack nearStack = new Stack();
		nearStack.add(placement(box("H1", true, 100, 50, 50), 800, 0, 0));
		Container near = container(1000, 200, 100, nearStack);

		Stack farStack = new Stack();
		farStack.add(placement(box("H1", true, 100, 50, 50), 200, 0, 0));
		Container far = container(1000, 200, 100, farStack);

		DoorSideRuleSupport.Score nearScore = DoorSideRuleSupport.score(new PackagerResult(List.of(near), 0, false));
		DoorSideRuleSupport.Score farScore = DoorSideRuleSupport.score(new PackagerResult(List.of(far), 0, false));

		assertThat(nearScore.isBetterThan(farScore)).isTrue();
	}

	@Test
	void penalizesDoorSideHouseBillSpreadFromDoorToInnerContainer() {
		Stack compactNearDoorStack = new Stack();
		compactNearDoorStack.add(placement(box("H1", true, 100, 50, 50), 800, 0, 0));
		compactNearDoorStack.add(placement(box("H1", true, 100, 50, 50), 900, 0, 0));
		Container compactNearDoor = container(1000, 200, 100, compactNearDoorStack);

		Stack touchesDoorButSpreadsInsideStack = new Stack();
		touchesDoorButSpreadsInsideStack.add(placement(box("H1", true, 100, 50, 50), 0, 0, 0));
		touchesDoorButSpreadsInsideStack.add(placement(box("H1", true, 100, 50, 50), 900, 0, 0));
		Container touchesDoorButSpreadsInside = container(1000, 200, 100, touchesDoorButSpreadsInsideStack);

		DoorSideRuleSupport.Score compactScore = DoorSideRuleSupport.score(new PackagerResult(List.of(compactNearDoor), 0, false));
		DoorSideRuleSupport.Score spreadScore = DoorSideRuleSupport.score(new PackagerResult(List.of(touchesDoorButSpreadsInside), 0, false));

		assertThat(compactScore.isBetterThan(spreadScore)).isTrue();
	}

	@Test
	void prefersDoorSideCandidateCloserToOriginBeforeDoorSideMirror() {
		Container container = container(1000, 200, 100);
		RulePlacementComparator comparator = new RulePlacementComparator(container);
		Box box = box("H1", true, 100, 50, 50);
		Placement originSide = placement(box, 0, 0, 0);
		Placement farFromOrigin = placement(box, 900, 0, 0);

		assertThat(comparator.compare(originSide, farFromOrigin)).isGreaterThan(0);
	}

	@Test
	void rejectsOtherHouseBillBetweenDoorSideHouseBillAndDoor() {
		Stack stack = new Stack();
		stack.add(placement(box("H1", true, 100, 50, 50), 700, 0, 0));
		stack.add(placement(box("H2", false, 100, 50, 50), 900, 0, 0));
		Container container = container(1000, 200, 100, stack);

		assertThat(DoorSideRuleSupport.isValid(new PackagerResult(List.of(container), 0, false))).isFalse();
	}

	@Test
	void allowsOtherHouseBillInsideDoorSideHouseBill() {
		Stack stack = new Stack();
		stack.add(placement(box("H1", true, 100, 50, 50), 900, 0, 0));
		stack.add(placement(box("H2", false, 100, 50, 50), 700, 0, 0));
		Container container = container(1000, 200, 100, stack);

		assertThat(DoorSideRuleSupport.isValid(new PackagerResult(List.of(container), 0, false))).isTrue();
	}

	@Test
	void allowsOtherHouseBillInDoorSideBandWhenItDoesNotBlockAccess() {
		Stack stack = new Stack();
		stack.add(placement(box("H1", true, 100, 50, 50), 800, 0, 0));
		stack.add(placement(box("H2", false, 100, 50, 50), 900, 60, 0));
		Container container = container(1000, 200, 100, stack);

		assertThat(DoorSideRuleSupport.isValid(new PackagerResult(List.of(container), 0, false))).isTrue();
	}

	@Test
	void rejectsOtherHouseBillInDoorSideBandWhenItBlocksAccess() {
		Stack stack = new Stack();
		stack.add(placement(box("H1", true, 100, 50, 50), 800, 0, 0));
		stack.add(placement(box("H2", false, 100, 50, 50), 900, 25, 0));
		Container container = container(1000, 200, 100, stack);

		assertThat(DoorSideRuleSupport.isValid(new PackagerResult(List.of(container), 0, false))).isFalse();
	}

	private static Container container(int dx, int dy, int dz) {
		return container(dx, dy, dz, new Stack());
	}

	private static Container container(int dx, int dy, int dz, Stack stack) {
		return Container.newBuilder()
				.withId("C1")
				.withSize(dx, dy, dz)
				.withMaxLoadWeight(10_000)
				.withStack(stack)
				.build();
	}

	private static Box box(String houseBsId, boolean doorSide, int dx, int dy, int dz) {
		return Box.newBuilder()
				.withId(houseBsId)
				.withSize(dx, dy, dz)
				.withWeight(1)
				.withRotate2D()
				.withProperty(PackingMapper.PROP_HOUSE_BS_ID, houseBsId)
				.withProperty(PackingMapper.PROP_DOOR_SIDE, doorSide)
				.build();
	}

	private static BoxStackValue stackValue(Box box) {
		return box.getStackValues()[0];
	}

	private static Placement placement(Box box, int x, int y, int z) {
		BoxStackValue stackValue = stackValue(box);
		return new Placement(stackValue, new DefaultPoint3D(x, y, z,
				x + stackValue.getDx() - 1,
				y + stackValue.getDy() - 1,
				z + stackValue.getDz() - 1));
	}
}
