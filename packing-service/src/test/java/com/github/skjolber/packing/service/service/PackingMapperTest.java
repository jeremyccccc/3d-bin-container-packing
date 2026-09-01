package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.service.dto.PackingRequest;

class PackingMapperTest {

	@Test
	void doesNotRoundExactQuantityUpBecauseOfFloatingPointNoise() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M1",
				  "containerLists":[{"id":"C1","size":40,"type":"HQ"}],
				  "houseBillList":[{
				    "houseBsId":"H1",
				    "desc":"全棉斜纹-D",
				    "totalNum":3,
				    "totalWeight":0,
				    "totalMeas":9.114,
				    "items":[{
				      "inboundId":"I1",
				      "num":3,
				      "weight":0,
				      "meas":9.114,
				      "size":{"length":155,"width":140,"height":140}
				    }]
				  }]
				}
				""", PackingRequest.class);

		PackingPlan plan = new PackingMapper().toPlan(request);

		assertThat(plan.boxItems()).hasSize(1);
		assertThat(plan.boxItems().get(0).getCount()).isEqualTo(3);
		assertThat(plan.warnings()).isEmpty();
	}

	@Test
	void mapsTopAndSelfStackNoPressToOneEffectiveNoPressRule() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M1",
				  "containerLists":[{"id":"C1","size":40,"type":"HQ"}],
				  "houseBillList":[{
				    "houseBsId":"H1",
				    "desc":"top",
				    "rule":{"CustomerMode":0,"HeightPosition":2,"DoorSide":false,"Method":1,"FlatHeight":null},
				    "totalNum":1,
				    "totalWeight":1,
				    "totalMeas":0.001,
				    "items":[{
				      "inboundId":"I1",
				      "num":1,
				      "weight":1,
				      "meas":0.001,
				      "size":{"length":10,"width":10,"height":10}
				    }]
				  }]
				}
				""", PackingRequest.class);

		PackingPlan plan = new PackingMapper().toPlan(request);

		assertThat(plan.boxItems()).hasSize(1);
		assertThat(plan.boxItems().get(0).getBox().<Boolean>getProperty(PackingMapper.PROP_NO_PRESS)).isTrue();
	}

	@Test
	void mapsDoorSideRule() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M1",
				  "containerLists":[{"id":"C1","size":40,"type":"HQ"}],
				  "houseBillList":[{
				    "houseBsId":"H1",
				    "desc":"door",
				    "rule":{"CustomerMode":0,"HeightPosition":0,"DoorSide":true,"Method":0,"FlatHeight":null},
				    "totalNum":1,
				    "totalWeight":1,
				    "totalMeas":0.001,
				    "items":[{
				      "inboundId":"I1",
				      "num":1,
				      "weight":1,
				      "meas":0.001,
				      "size":{"length":10,"width":10,"height":10}
				    }]
				  }]
				}
				""", PackingRequest.class);

		PackingPlan plan = new PackingMapper().toPlan(request);

		assertThat(plan.boxItems()).hasSize(1);
		assertThat(plan.boxItems().get(0).getBox().<Boolean>getProperty(PackingMapper.PROP_DOOR_SIDE)).isTrue();
	}
}
