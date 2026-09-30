package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Box;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;

class WholeOrderCandidateSearchTest {

	@Test
	void disabledProbePreservesStaticCandidateOrder() {
		Container container = Container.newBuilder().withId("C")
				.withSize(100, 100, 100).withMaxLoadWeight(10_000).build();
		List<ContainerItem> containers = List.of(new ContainerItem(container, 1));
		List<WholeOrderAssignmentSolver.Candidate> candidates = List.of(
				candidate("first", item("A")), candidate("second", item("B")));
		List<String> validated = new ArrayList<>();

		WholeOrderCandidateSearch.Result result = new WholeOrderCandidateSearch(
				PlacementSupport.DEFAULT_POLICY, false, 8, 8).search(
						candidates, containers, new WholeOrderPackingCache(), 1L,
						System.currentTimeMillis() + 5_000L, (candidate, attempt) -> {
							validated.add(candidate.source());
							return attempt == 2
									? new PackagerResult(List.of(container.clone()), 1L, false) : null;
						});

		assertThat(validated).containsExactly("first", "second");
		assertThat(result.attempted()).isEqualTo(2);
		assertThat(result.result()).isNotNull();
	}

	private static WholeOrderAssignmentSolver.Candidate candidate(String source, BoxItem item) {
		WholeOrderCandidateScorer.Score score = new WholeOrderCandidateScorer.Score(
				1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1, 0.0, List.of(1.0));
		return new WholeOrderAssignmentSolver.Candidate(
				List.of(List.of(item)), source, 0, source, score);
	}

	private static BoxItem item(String id) {
		Box box = Box.newBuilder().withId(id).withSize(10, 10, 10)
				.withWeight(1).withRotate3D().build();
		return new BoxItem(box, 1);
	}
}
