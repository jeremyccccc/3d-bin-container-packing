package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.service.dto.AllocatedContainerDto;
import com.github.skjolber.packing.service.dto.AllocatedHouseBillDto;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;

class WholeOrderPackingTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final PackingService service = new PackingService(new PackingMapper(), new PackingEngine());

	@Test
	void keepsExistingSingleContainerPathSuccessful() throws Exception {
		PackingResponse response = service.pack(read("""
				{"masterBsId":"M1","containerLists":[{"id":"C1","size":20,"type":"GP"}],
				 "houseBillList":[{"houseBsId":"H1","totalNum":2,"totalWeight":20,"totalMeas":2,
				   "items":[{"inboundId":"I1","num":2,"weight":20,"meas":2,
				     "size":{"length":100,"width":100,"height":100}}]}]}
				"""));

		assertThat(response.success()).isTrue();
		assertThat(response.containerLists()).singleElement()
				.extracting(container -> container.allocatedHouseBillList().size())
				.isEqualTo(1);
	}

	@Test
	void assignsEveryHouseBillToExactlyOneContainer() throws Exception {
		PackingResponse response = service.pack(read("""
				{"masterBsId":"M2",
				 "containerLists":[{"id":"C1","size":20,"type":"GP"},{"id":"C2","size":20,"type":"GP"}],
				 "houseBillList":[
				   {"houseBsId":"H1","totalNum":2,"totalWeight":20,"totalMeas":17.5,"items":[
				     {"inboundId":"I1","num":1,"weight":10,"meas":8.75,"size":{"length":250,"width":175,"height":200}},
				     {"inboundId":"I2","num":1,"weight":10,"meas":8.75,"size":{"length":250,"width":175,"height":200}}]},
				   {"houseBsId":"H2","totalNum":2,"totalWeight":20,"totalMeas":17.5,"items":[
				     {"inboundId":"I3","num":1,"weight":10,"meas":8.75,"size":{"length":250,"width":175,"height":200}},
				     {"inboundId":"I4","num":1,"weight":10,"meas":8.75,"size":{"length":250,"width":175,"height":200}}]}
				 ]}
				"""));

		assertThat(response.success()).isTrue();
		Map<String, Integer> occurrences = houseBillOccurrences(response);
		assertThat(occurrences).containsEntry("H1", 1).containsEntry("H2", 1);
	}

	@Test
	void supportsTwoCandidateWorkersWhenExplicitlyEnabled() throws Exception {
		PackingEngine parallelEngine = new PackingEngine(PlacementSupport.DEFAULT_POLICY, false, true,
				WholeOrderAssignmentStrategy.FILL_FIRST, 0.85, false, 30_000L, 16, 16, 2);
		PackingService parallelService = new PackingService(new PackingMapper(), parallelEngine);
		PackingResponse response = parallelService.pack(read("""
				{"masterBsId":"M-PARALLEL",
				 "containerLists":[{"id":"C1","size":20,"type":"GP"},{"id":"C2","size":20,"type":"GP"}],
				 "houseBillList":[
				   {"houseBsId":"H1","totalNum":1,"totalWeight":10,"totalMeas":1,"items":[
				     {"inboundId":"I1","num":1,"weight":10,"meas":1,"size":{"length":100,"width":100,"height":100}}]},
				   {"houseBsId":"H2","totalNum":1,"totalWeight":10,"totalMeas":1,"items":[
				     {"inboundId":"I2","num":1,"weight":10,"meas":1,"size":{"length":100,"width":100,"height":100}}]}
				 ]}
				"""));

		assertThat(response.success()).isTrue();
		assertThat(houseBillOccurrences(response)).containsEntry("H1", 1).containsEntry("H2", 1);
	}

	@Test
	void addsA40HqAndStillKeepsWholeOrdersTogetherWhenOriginalContainersCannotFit() throws Exception {
		PackingResponse response = service.pack(read("""
				{"masterBsId":"M3",
				 "containerLists":[{"id":"C1","size":20,"type":"GP"},{"id":"C2","size":20,"type":"GP"}],
				 "houseBillList":[
				   {"houseBsId":"H1","totalNum":2,"totalWeight":20,"totalMeas":22.014,"items":[{"inboundId":"I1","num":2,"weight":20,"meas":22.014,"size":{"length":196,"width":235,"height":239}}]},
				   {"houseBsId":"H2","totalNum":2,"totalWeight":20,"totalMeas":22.014,"items":[{"inboundId":"I2","num":2,"weight":20,"meas":22.014,"size":{"length":196,"width":235,"height":239}}]},
				   {"houseBsId":"H3","totalNum":2,"totalWeight":20,"totalMeas":22.014,"items":[{"inboundId":"I3","num":2,"weight":20,"meas":22.014,"size":{"length":196,"width":235,"height":239}}]}
				 ]}
				"""));

		assertThat(response.success()).isTrue();
		assertThat(response.warnings()).anyMatch(value -> value.startsWith("AUTO_40HQ_FALLBACK"));
		Map<String, Integer> occurrences = houseBillOccurrences(response);
		assertThat(occurrences).containsEntry("H1", 1).containsEntry("H2", 1).containsEntry("H3", 1);
	}

	private PackingRequest read(String json) throws Exception {
		return objectMapper.readValue(json, PackingRequest.class);
	}

	private static Map<String, Integer> houseBillOccurrences(PackingResponse response) {
		Map<String, Integer> result = new HashMap<>();
		for (AllocatedContainerDto container : response.containerLists()) {
			for (AllocatedHouseBillDto houseBill : container.allocatedHouseBillList()) {
				result.merge(houseBill.houseBsId(), 1, Integer::sum);
			}
		}
		return result;
	}
}
