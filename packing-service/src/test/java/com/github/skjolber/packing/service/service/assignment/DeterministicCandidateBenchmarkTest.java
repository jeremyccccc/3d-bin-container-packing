package com.github.skjolber.packing.service.service.assignment;

import com.github.skjolber.packing.service.service.PackingPlan;
import com.github.skjolber.packing.service.service.mapping.PackingMapper;
import com.github.skjolber.packing.service.service.rule.PlacementSupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.service.dto.HouseBillDto;
import com.github.skjolber.packing.service.dto.PackingRequest;

/** Optional real-data determinism check enabled with -Dpacking.benchmark.json=... */
class DeterministicCandidateBenchmarkTest {

	@Test
	void realDataProducesTheSame256CandidatesForEveryInputOrder() throws Exception {
		String sourcePath = System.getProperty("packing.benchmark.json");
		Assumptions.assumeTrue(sourcePath != null && !sourcePath.isBlank(),
				"Set -Dpacking.benchmark.json to run the real-data candidate benchmark");
		PackingRequest source = new ObjectMapper().readValue(Files.readString(Path.of(sourcePath)), PackingRequest.class);
		List<HouseBillDto> ascending = new ArrayList<>(source.houseBillList());
		ascending.sort(Comparator.comparingLong(item -> Long.parseLong(item.houseBsId())));
		List<HouseBillDto> descending = new ArrayList<>(ascending);
		Collections.reverse(descending);
		List<HouseBillDto> shuffled = new ArrayList<>(ascending);
		Collections.shuffle(shuffled, new Random(20260928L));

		List<String> expected = candidates(source, ascending);
		assertThat(expected).hasSize(256);
		assertThat(candidates(source, descending)).containsExactlyElementsOf(expected);
		assertThat(candidates(source, shuffled)).containsExactlyElementsOf(expected);

		List<String> expectedProbeOrder = probeOrder(source, ascending);
		assertThat(probeOrder(source, descending)).containsExactlyElementsOf(expectedProbeOrder);
		assertThat(probeOrder(source, shuffled)).containsExactlyElementsOf(expectedProbeOrder);
	}

	private static List<String> probeOrder(PackingRequest source, List<HouseBillDto> houseBills) {
		PackingRequest reordered = new PackingRequest(source.masterBsId(), source.containerLists(), houseBills);
		PackingPlan plan = new PackingMapper().toPlan(reordered);
		List<WholeOrderAssignmentSolver.Candidate> candidates = new WholeOrderAssignmentSolver().candidates(
				plan, WholeOrderAssignmentStrategy.FILL_FIRST, 0.85);
		List<WholeOrderCandidateSelector.IndexedCandidate> window = new ArrayList<>();
		for (int index = 0; index < Math.min(8, candidates.size()); index++) {
			window.add(new WholeOrderCandidateSelector.IndexedCandidate(candidates.get(index), index));
		}
		List<ContainerItem> containers = new ArrayList<>();
		for (ContainerItem item : plan.containerItems()) {
			for (int index = 0; index < item.getCount(); index++) {
				containers.add(new ContainerItem(item.getContainer(), 1));
			}
		}
		return new WholeOrderCandidateProbeRanker(PlacementSupport.DEFAULT_POLICY, 8)
				.rank(window, containers, new WholeOrderPackingCache(), 0L).stream()
				.map(value -> value.indexed().candidate().signature()).toList();
	}

	private static List<String> candidates(PackingRequest source, List<HouseBillDto> houseBills) {
		return candidateViews(generatedCandidates(source, houseBills));
	}

	private static List<WholeOrderAssignmentSolver.Candidate> generatedCandidates(
			PackingRequest source, List<HouseBillDto> houseBills) {
		PackingRequest reordered = new PackingRequest(source.masterBsId(), source.containerLists(), houseBills);
		PackingPlan plan = new PackingMapper().toPlan(reordered);
		return new WholeOrderAssignmentSolver().candidates(
				plan, WholeOrderAssignmentStrategy.FILL_FIRST, 0.85);
	}

	private static List<String> candidateViews(
			List<WholeOrderAssignmentSolver.Candidate> candidates) {
		return candidates.stream()
				.map(candidate -> candidate.signature()
						+ "|" + candidate.source()
						+ "|" + candidate.generationIndex()
						+ "|" + Double.doubleToLongBits(candidate.score().total())
						+ "|" + candidate.score().validationOrder()
						+ "|" + candidate.itemsByContainer().stream()
								.map(items -> items.stream().map(PackingDeterminism::boxItemKey).toList())
								.toList())
				.toList();
	}
}
