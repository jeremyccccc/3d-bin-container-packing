package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.service.dto.AllocatedContainerDto;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;

class PackingServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final PackingService service = new PackingService(new PackingMapper(), new PackingEngine());

	@Test
	void packsCsharpJsonAndKeepsOriginalReturnedNum() throws Exception {
		PackingRequest request = objectMapper.readValue("""
				{
					"masterBsId": "136356701",
					"containerLists": [{
						"id": "3093501",
						"size": 40,
						"type": "GP"
					}, {
						"id": "3093601",
						"size": 40,
						"type": "HQ"
					}],
					"houseBillList": [{
						"houseBsId": "136356801",
						"desc": "衣帽架",
						"rule": {
							"CustomerMode": 1,
							"HeightPosition": 0,
							"DoorSide": false,
							"Method": 0,
							"FlatHeight": null
						},
						"totalNum": 33,
						"totalWeight": 456.75,
						"totalMeas": 2.21,
						"items": [{
							"inboundId": "-1",
							"num": 33,
							"size": {
								"length": 33,
								"width": 33,
								"height": 33
							},
							"weight": 456.75,
							"meas": 2.21
						}]
					}, {
						"houseBsId": "136356901",
						"desc": "衣帽架",
						"rule": {
							"CustomerMode": 2,
							"HeightPosition": 2,
							"DoorSide": true,
							"Method": 2,
							"FlatHeight": 10
						},
						"totalNum": 34,
						"totalWeight": 536,
						"totalMeas": 3.17,
						"items": [{
							"inboundId": "-1",
							"num": 34,
							"size": {
								"length": 34,
								"width": 34,
								"height": 34
							},
							"weight": 536.0,
							"meas": 3.17
						}]
					}]
				}
				""", PackingRequest.class);

		PackingResponse response = service.pack(request);

		assertThat(response.masterBsId()).isEqualTo("136356701");
		assertThat(response.containerLists()).hasSize(2);

		AllocatedContainerDto gp = response.containerLists().get(0);
		AllocatedContainerDto hq = response.containerLists().get(1);
		assertThat(gp.allocatedHouseBillList()).isEmpty();
		assertThat(hq.allocatedHouseBillList()).hasSize(2);

		int returnedNum = hq.allocatedHouseBillList()
				.stream()
				.flatMap(houseBill -> houseBill.items().stream())
				.mapToInt(item -> item.num())
				.sum();
		assertThat(returnedNum).isEqualTo(67);
	}
}
