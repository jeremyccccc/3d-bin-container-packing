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
							"CustomerMode": 0,
							"HeightPosition": 0,
							"DoorSide": false,
							"Method": 0,
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

		assertThat(response.success()).isTrue();
		assertThat(response.masterBsId()).isEqualTo("136356701");
		assertThat(response.containerLists()).hasSize(2);

		assertThat(response.containerLists())
				.flatExtracting(AllocatedContainerDto::allocatedHouseBillList)
				.hasSize(2);

		int returnedNum = response.containerLists().stream()
				.flatMap(container -> container.allocatedHouseBillList().stream())
				.flatMap(houseBill -> houseBill.items().stream())
				.mapToInt(item -> item.num())
				.sum();
		assertThat(returnedNum).isEqualTo(67);
	}

	@Test
	void packsHeightPositionTopAsNoPressRule() throws Exception {
		PackingRequest request = requestWithRule(2, false, 0);

		PackingResponse response = service.pack(request);

		assertThat(response.success()).isTrue();
		assertThat(response.message()).isEqualTo("装箱成功");
		assertThat(response.containerLists().get(0).allocatedHouseBillList()).hasSize(1);
	}

	@Test
	void rejectsInvalidRuleValues() throws Exception {
		PackingRequest request = requestWithRule(3, false, 0);

		PackingResponse response = service.pack(request);

		assertThat(response.success()).isFalse();
		assertThat(response.message()).contains("装箱规则高度位置无效");
	}

	@Test
	void packsHouseBillWithBottomRule() throws Exception {
		PackingRequest request = requestWithRule(1, false, 0);

		PackingResponse response = service.pack(request);

		assertThat(response.success()).isTrue();
		assertThat(response.message()).isEqualTo("装箱成功");
		assertThat(response.containerLists().get(0).allocatedHouseBillList()).hasSize(1);
	}

	@Test
	void packsHouseBillWithDoorSideRule() throws Exception {
		PackingResponse response = service.pack(requestWithRule(0, true, 0));

		assertThat(response.success()).isTrue();
		assertThat(response.message()).isEqualTo("装箱成功");
		assertThat(response.containerLists().get(0).allocatedHouseBillList()).hasSize(1);
	}

	@Test
	void packsSelfStackNoPressAsNoPressRule() throws Exception {
		PackingResponse response = service.pack(requestWithRule(0, false, 1));

		assertThat(response.success()).isTrue();
		assertThat(response.message()).isEqualTo("装箱成功");
		assertThat(response.containerLists().get(0).allocatedHouseBillList()).hasSize(1);
	}

	@Test
	void packsTopAndSelfStackNoPressTogetherAsOneRule() throws Exception {
		PackingResponse response = service.pack(requestWithRule(2, false, 1));

		assertThat(response.success()).isTrue();
		assertThat(response.message()).isEqualTo("装箱成功");
		assertThat(response.containerLists().get(0).allocatedHouseBillList()).hasSize(1);
	}

	@Test
	void rejectsFlatUntilItIsDefined() throws Exception {
		PackingResponse response = service.pack(requestWithRule(0, false, 2));

		assertThat(response.success()).isFalse();
		assertThat(response.message()).contains("暂不支持平铺规则");
	}

	private PackingRequest requestWithRule(int heightPosition, boolean doorSide, int method) throws Exception {
		return objectMapper.readValue("""
				{
				  "masterBsId": "M1",
				  "containerLists": [{"id":"C1","size":40,"type":"HQ"}],
				  "houseBillList": [{
				    "houseBsId":"H1",
				    "desc":"test",
				    "rule":{"CustomerMode":0,"HeightPosition":%d,"DoorSide":%s,"Method":%d,"FlatHeight":null},
				    "totalNum":1,"totalWeight":1,"totalMeas":0.001,
				    "items":[{"inboundId":"I1","num":1,"weight":1,"meas":0.001,
				      "size":{"length":10,"width":10,"height":10}}]
				  }]
				}
				""".formatted(heightPosition, doorSide, method), PackingRequest.class);
	}
}
