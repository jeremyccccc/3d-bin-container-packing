package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.service.service.mapping.PackingMapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.service.dto.PackingRequest;

class WholeOrderCandidateScorerTest {

	@Test
	void candidateGenerationDoesNotDependOnHouseBillOrder() throws Exception {
		PackingPlan ascending = plan();
		List<List<BoxItem>> reversedGroups = groups(ascending);
		Collections.reverse(reversedGroups);
		PackingPlan descending = withGroups(ascending, reversedGroups);
		List<List<BoxItem>> shuffledGroups = groups(ascending);
		Collections.shuffle(shuffledGroups, new Random(987654321L));
		PackingPlan shuffled = withGroups(ascending, shuffledGroups);

		WholeOrderAssignmentSolver solver = new WholeOrderAssignmentSolver();
		for (WholeOrderAssignmentStrategy strategy : WholeOrderAssignmentStrategy.values()) {
			List<String> expected = candidateViews(solver.candidates(ascending, strategy, 0.85));
			assertThat(candidateViews(solver.candidates(descending, strategy, 0.85)))
					.as("descending %s candidates", strategy).containsExactlyElementsOf(expected);
			assertThat(candidateViews(solver.candidates(shuffled, strategy, 0.85)))
					.as("shuffled %s candidates", strategy).containsExactlyElementsOf(expected);
		}
	}

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
	void defaultCandidateGenerationIsExactlyTheBalancedStrategy() throws Exception {
		PackingPlan plan = plan();
		WholeOrderAssignmentSolver solver = new WholeOrderAssignmentSolver();

		List<WholeOrderAssignmentSolver.Candidate> defaults = solver.candidates(plan);
		List<WholeOrderAssignmentSolver.Candidate> explicit = solver.candidates(
				plan, WholeOrderAssignmentStrategy.BALANCED, 0.85);

		assertThat(defaults).hasSameSizeAs(explicit);
		for (int i = 0; i < defaults.size(); i++) {
			assertThat(defaults.get(i).generationIndex()).isEqualTo(explicit.get(i).generationIndex());
			assertThat(defaults.get(i).source()).isEqualTo(explicit.get(i).source());
			assertThat(defaults.get(i).score().total()).isEqualTo(explicit.get(i).score().total());
			assertThat(partition(defaults.get(i))).isEqualTo(partition(explicit.get(i)));
		}
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

	@Test
	void fillFirstUsesNoMoreContainersAndFrontLoadsTheCandidate() throws Exception {
		PackingPlan plan = plan();
		WholeOrderAssignmentSolver solver = new WholeOrderAssignmentSolver();

		WholeOrderAssignmentSolver.Candidate balanced = solver.candidates(plan).get(0);
		WholeOrderAssignmentSolver.Candidate fillFirst = solver.candidates(
				plan, WholeOrderAssignmentStrategy.FILL_FIRST, 0.85).get(0);

		assertThat(fillFirst.score().usedContainers())
				.isLessThanOrEqualTo(balanced.score().usedContainers());
		List<Long> volumes = fillFirst.itemsByContainer().stream()
				.map(items -> items.stream().mapToLong(BoxItem::getVolume).sum())
				.toList();
		int lastUsed = -1;
		for (int i = 0; i < volumes.size(); i++) if (volumes.get(i) > 0L) lastUsed = i;
		for (int i = 0; i < lastUsed; i++) {
			assertThat(volumes.get(i)).isPositive();
		}
	}

	@Test
	void parsesAssignmentStrategyConfiguration() {
		assertThat(WholeOrderAssignmentStrategy.parse("balanced"))
				.isEqualTo(WholeOrderAssignmentStrategy.BALANCED);
		assertThat(WholeOrderAssignmentStrategy.parse("fill-first"))
				.isEqualTo(WholeOrderAssignmentStrategy.FILL_FIRST);
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> WholeOrderAssignmentStrategy.parse("unknown"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void createsTheFormalWholeOrderCandidateForOneContainer() throws Exception {
		PackingPlan multiple = plan();
		PackingPlan single = new PackingPlan(
				List.of(multiple.requestedContainers().get(0)),
				List.of(multiple.containerItems().get(0)),
				multiple.boxItems(), multiple.cargoLines(), multiple.warnings());

		List<WholeOrderAssignmentSolver.Candidate> candidates = new WholeOrderAssignmentSolver()
				.candidates(single, WholeOrderAssignmentStrategy.FILL_FIRST, 0.85);

		assertThat(candidates).singleElement().satisfies(candidate -> {
			assertThat(candidate.source()).isEqualTo("single-container");
			assertThat(candidate.itemsByContainer()).hasSize(1);
			assertThat(candidate.itemsByContainer().get(0)).hasSameSizeAs(single.boxItems());
		});
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

	private static List<List<BoxItem>> groups(PackingPlan plan) {
		Map<String, List<BoxItem>> groups = new LinkedHashMap<>();
		for (BoxItem item : plan.boxItems()) {
			String id = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			groups.computeIfAbsent(id, ignored -> new ArrayList<>()).add(item);
		}
		return groups.values().stream().map(List::copyOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}

	private static PackingPlan withGroups(PackingPlan plan, List<List<BoxItem>> groups) {
		List<BoxItem> items = groups.stream().flatMap(List::stream).toList();
		return new PackingPlan(plan.requestedContainers(), plan.containerItems(), List.copyOf(items),
				plan.cargoLines(), plan.warnings());
	}

	private static List<String> candidateViews(List<WholeOrderAssignmentSolver.Candidate> candidates) {
		return candidates.stream().map(candidate -> candidate.signature()
				+ "|source=" + candidate.source()
				+ "|generation=" + candidate.generationIndex()
				+ "|score=" + Double.doubleToLongBits(candidate.score().total())
				+ "|validation=" + candidate.score().validationOrder()
				+ "|items=" + candidate.itemsByContainer().stream()
						.map(items -> items.stream().map(PackingDeterminism::boxItemKey).toList().toString())
						.toList())
				.toList();
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
				    {"houseBsId":"H1","totalNum":15,"totalWeight":123,"totalMeas":12.375,
				     "items":[
				       {"inboundId":"I1-B","num":3,"weight":3,"meas":0.375,
				        "size":{"length":50,"width":50,"height":50}},
				       {"inboundId":"I1-A","num":12,"weight":120,"meas":12,
				        "size":{"length":100,"width":100,"height":100}}
				     ]},
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
