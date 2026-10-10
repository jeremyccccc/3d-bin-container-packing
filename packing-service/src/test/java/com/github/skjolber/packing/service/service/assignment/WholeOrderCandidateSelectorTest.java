package com.github.skjolber.packing.service.service.assignment;

import com.github.skjolber.packing.service.service.assignment.WholeOrderAssignmentSolver;
import com.github.skjolber.packing.service.service.assignment.WholeOrderCandidateScorer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;

class WholeOrderCandidateSelectorTest {

	@Test
	void preservesStaticOrderAndPrunesProvenFailures() {
		Container container = Container.newBuilder().withId("C")
				.withSize(100, 100, 100).withMaxLoadWeight(10_000).build();
		List<ContainerItem> containers = List.of(new ContainerItem(container, 1));
		WholeOrderAssignmentSolver.Candidate unknown = candidate("unknown", item("A"), 1.0);
		WholeOrderAssignmentSolver.Candidate reusable = candidate("reusable", item("B"), 1.0);
		WholeOrderAssignmentSolver.Candidate impossible = candidate("impossible", item("C"), 0.5);

		WholeOrderPackingCache cache = new WholeOrderPackingCache();
		cache.putSuccess(container, reusable.itemsByContainer().get(0),
				new PackagerResult(List.of(container.clone()), 25L, false), 25L);
		cache.putFailure(container, impossible.itemsByContainer().get(0), 30L, true);

		WholeOrderCandidateSelector.Selection selection = new WholeOrderCandidateSelector().select(
				List.of(indexed(unknown, 0), indexed(reusable, 1), indexed(impossible, 2)),
				containers, cache);

		assertThat(selection.indexed().candidate()).isSameAs(unknown);
		assertThat(selection.evidence().successes()).isZero();
		assertThat(selection.evidence().savedMillis()).isZero();
		assertThat(selection.pruned()).isEqualTo(1);
	}

	private static WholeOrderCandidateSelector.IndexedCandidate indexed(
			WholeOrderAssignmentSolver.Candidate candidate, int index) {
		return new WholeOrderCandidateSelector.IndexedCandidate(candidate, index);
	}

	private static WholeOrderAssignmentSolver.Candidate candidate(
			String signature, BoxItem item, double total) {
		WholeOrderCandidateScorer.Score score = new WholeOrderCandidateScorer.Score(
				total, total, 0.0, 0.0, 0.0, 0.0, 1, 0.0, List.of(total));
		return new WholeOrderAssignmentSolver.Candidate(
				List.of(List.of(item)), signature, 0, signature, score);
	}

	private static BoxItem item(String id) {
		Box box = Box.newBuilder().withId(id).withSize(10, 10, 10)
				.withWeight(1).withRotate3D().build();
		return new BoxItem(box, 1);
	}
}
