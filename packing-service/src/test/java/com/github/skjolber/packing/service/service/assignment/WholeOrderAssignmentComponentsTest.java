package com.github.skjolber.packing.service.service.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.skjolber.packing.api.Container;

class WholeOrderAssignmentComponentsTest {

	@Test
	void greedySeedIsDeterministicAndWithinCapacity() {
		List<WholeOrderGroup> groups = groups(300, 250, 200, 150);
		List<Container> containers = containers(2, 1_000);
		WholeOrderGreedySeeder seeder = new WholeOrderGreedySeeder();

		int[] first = seeder.seed(groups, containers, 7,
				WholeOrderAssignmentStrategy.FILL_FIRST, 0.85);
		int[] second = seeder.seed(groups, containers, 7,
				WholeOrderAssignmentStrategy.FILL_FIRST, 0.85);

		assertThat(first).containsExactly(second);
		for (long load : WholeOrderAssignmentSupport.loads(groups, first, 2, true)) {
			assertThat(load).isLessThanOrEqualTo(1_000);
		}
	}

	@Test
	void neighborhoodGeneratesOnlyUniqueCapacitySafeCandidates() {
		List<WholeOrderGroup> groups = groups(400, 300, 200);
		List<Container> containers = containers(2, 1_000);
		List<WholeOrderAssignmentSolver.Candidate> candidates = new ArrayList<>();
		Set<String> signatures = new HashSet<>();
		int[] base = {0, 0, 1};
		WholeOrderAssignmentSupport.addCandidate(
				candidates, signatures, groups, containers, base, "base");

		new WholeOrderNeighborhoodGenerator().addLocalMoves(
				candidates, signatures, groups, containers, base, 256);

		assertThat(candidates).hasSizeGreaterThan(1).hasSizeLessThanOrEqualTo(7);
		assertThat(candidates.stream().map(WholeOrderAssignmentSolver.Candidate::signature))
				.doesNotHaveDuplicates();
		assertThat(candidates.stream().skip(1).map(WholeOrderAssignmentSolver.Candidate::source))
				.allMatch(source -> source.equals("local-move") || source.equals("local-swap"));
	}

	@Test
	void systematicSearchHonorsCandidateAndNodeLimits() {
		List<WholeOrderGroup> groups = groups(350, 250, 150);
		List<Container> containers = containers(2, 1_000);
		List<WholeOrderAssignmentSolver.Candidate> candidates = new ArrayList<>();
		Set<String> signatures = new HashSet<>();

		new WholeOrderSystematicSearch(3, 100).addCandidates(
				candidates, signatures, groups, containers);

		assertThat(candidates).isNotEmpty().hasSizeLessThanOrEqualTo(3);
		assertThat(candidates).allMatch(candidate -> candidate.source().equals("systematic"));
		assertThat(candidates.stream().map(WholeOrderAssignmentSolver.Candidate::signature))
				.doesNotHaveDuplicates();
	}

	private static List<WholeOrderGroup> groups(long... volumes) {
		List<WholeOrderGroup> result = new ArrayList<>();
		for (int index = 0; index < volumes.length; index++) {
			result.add(new WholeOrderGroup("H" + index, List.of(), volumes[index],
					volumes[index], volumes[index], "H" + index));
		}
		return result;
	}

	private static List<Container> containers(int count, int capacity) {
		List<Container> result = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			result.add(Container.newBuilder().withId("C" + index)
					.withSize(10, 10, capacity / 100).withMaxLoadWeight(capacity).build());
		}
		return result;
	}
}
