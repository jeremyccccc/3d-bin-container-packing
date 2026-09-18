package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.service.dto.PackingRequest;

class WholeOrderCandidateScorerTest {

	@Test
	void ranksCandidatesDeterministicallyByAscendingGeometryRisk() throws Exception {
		PackingPlan plan = plan();
		WholeOrderAssignmentSolver solver = new WholeOrderAssignmentSolver();

		List<WholeOrderAssignmentSolver.Candidate> first = solver.candidates(plan);
		List<WholeOrderAssignmentSolver.Candidate> second = solver.candidates(plan);

		assertThat(first).isNotEmpty();
		assertThat(first).extracting(candidate -> candidate.score().total()).isSorted();
		assertThat(first).extracting(WholeOrderAssignmentSolver.Candidate::generationIndex)
				.containsExactlyElementsOf(second.stream()
						.map(WholeOrderAssignmentSolver.Candidate::generationIndex).toList());
	}

	@Test
	void validatesThePredictedHardestContainerFirst() throws Exception {
		WholeOrderAssignmentSolver.Candidate candidate = new WholeOrderAssignmentSolver().candidates(plan()).get(0);
		List<Integer> order = candidate.score().validationOrder();

		assertThat(order).containsExactlyInAnyOrderElementsOf(
				java.util.stream.IntStream.range(0, candidate.itemsByContainer().size()).boxed().toList());
		for (int i = 1; i < order.size(); i++) {
			assertThat(candidate.score().containerRisks().get(order.get(i - 1)))
					.isGreaterThanOrEqualTo(candidate.score().containerRisks().get(order.get(i)));
		}
	}

	@Test
	void removesAssignmentsWhichOnlyRenameEquivalentContainers() throws Exception {
		List<WholeOrderAssignmentSolver.Candidate> candidates = new WholeOrderAssignmentSolver().candidates(plan());
		Set<String> partitions = new HashSet<>();
		for (WholeOrderAssignmentSolver.Candidate candidate : candidates) {
			assertThat(partitions.add(partition(candidate))).isTrue();
		}
	}

	private static String partition(WholeOrderAssignmentSolver.Candidate candidate) {
		List<String> containers = new ArrayList<>();
		for (List<BoxItem> items : candidate.itemsByContainer()) {
			containers.add(items.stream()
					.map(item -> item.getBox().<String>getProperty(PackingMapper.PROP_HOUSE_BS_ID))
					.distinct().sorted().reduce((left, right) -> left + "," + right).orElse(""));
		}
		containers.sort(String::compareTo);
		return String.join("|", containers);
	}

	private static PackingPlan plan() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M-RANK",
				  "containerLists":[
				    {"id":"C1","size":20,"type":"GP"},
				    {"id":"C2","size":20,"type":"GP"},
				    {"id":"C3","size":20,"type":"GP"}
				  ],
				  "houseBillList":[
				    {"houseBsId":"H1","totalNum":12,"totalWeight":120,"totalMeas":12,
				     "items":[{"inboundId":"I1","num":12,"weight":120,"meas":12,
				       "size":{"length":100,"width":100,"height":100}}]},
				    {"houseBsId":"H2","totalNum":8,"totalWeight":80,"totalMeas":8,
				     "items":[{"inboundId":"I2","num":8,"weight":80,"meas":8,
				       "size":{"length":200,"width":100,"height":50}}]},
				    {"houseBsId":"H3","totalNum":6,"totalWeight":60,"totalMeas":6,
				     "items":[{"inboundId":"I3","num":6,"weight":60,"meas":6,
				       "size":{"length":250,"width":120,"height":80}}]},
				    {"houseBsId":"H4","totalNum":4,"totalWeight":40,"totalMeas":4,
				     "items":[{"inboundId":"I4","num":4,"weight":40,"meas":4,
				       "size":{"length":300,"width":150,"height":100}}]}
				  ]
				}
				""", PackingRequest.class);
		return new PackingMapper().toPlan(request);
	}
}
