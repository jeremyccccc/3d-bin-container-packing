package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.service.dto.PackingRequest;

class PackingMapperTest {

	@Test
	void doesNotRoundExactQuantityBecauseOfFloatingPointNoise() throws Exception {
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
	void roundsMeasuredQuantityToNearestWholeBox() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M1",
				  "containerLists":[{"id":"C1","size":40,"type":"HQ"}],
				  "houseBillList":[{
				    "houseBsId":"H1",
				    "desc":"cargo",
				    "totalNum":2,
				    "totalWeight":0,
				    "totalMeas":0.0020010853344186676,
				    "items":[{
				      "inboundId":"I1",
				      "num":2,
				      "weight":0,
				      "meas":0.0020010853344186676,
				      "size":{"length":10,"width":10,"height":10}
				    }]
				  }]
				}
				""", PackingRequest.class);

		PackingPlan plan = new PackingMapper().toPlan(request);

		assertThat(plan.boxItems()).hasSize(1);
		assertThat(plan.boxItems().get(0).getCount()).isEqualTo(2);
		assertThat(plan.warnings()).singleElement().asString().contains(
				"MEAS_QUANTITY_ROUNDED houseBsId=H1 inboundId=I1",
				"rounded=2");
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

	@Test
	void mapsHouseBillCustomer() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M1",
				  "containerLists":[{"id":"C1","size":40,"type":"HQ"}],
				  "houseBillList":[{
				    "houseBsId":"H1",
				    "desc":"cargo",
				    "customer":"ACME",
				    "rule":{"CustomerMode":0,"HeightPosition":0,"DoorSide":false,"Method":0,"FlatHeight":null},
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

		assertThat(plan.cargoLines()).hasSize(1);
		assertThat(plan.cargoLines().get(0).customer()).isEqualTo("ACME");
		assertThat(plan.boxItems().get(0).getBox().<String>getProperty(PackingMapper.PROP_CUSTOMER)).isEqualTo("ACME");
	}

	@Test
	void mapsSupportedContainerTypes() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M1",
				  "containerLists":[
				    {"id":"C20GP","size":20,"type":"GP"},
				    {"id":"C40GP","size":40,"type":"GP"},
				    {"id":"C40HQ","size":40,"type":"HQ"},
				    {"id":"C45HQ","size":45,"type":"HQ"},
				    {"id":"C45GP","size":45,"type":"GP"}
				  ],
				  "houseBillList":[]
				}
				""", PackingRequest.class);

		PackingPlan plan = new PackingMapper().toPlan(request);

		assertThat(plan.containerItems()).hasSize(4);
		assertThat(plan.containerItems().get(0).getContainer().getId()).isEqualTo("C20GP");
		assertThat(plan.containerItems().get(0).getContainer().getLoadDx()).isEqualTo(5898);
		assertThat(plan.containerItems().get(0).getContainer().getLoadDy()).isEqualTo(2352);
		assertThat(plan.containerItems().get(0).getContainer().getLoadDz()).isEqualTo(2393);
		assertThat(plan.containerItems().get(0).getContainer().getMaxLoadWeight()).isEqualTo(28220_000);

		assertThat(plan.containerItems().get(1).getContainer().getId()).isEqualTo("C40GP");
		assertThat(plan.containerItems().get(1).getContainer().getLoadDx()).isEqualTo(12032);
		assertThat(plan.containerItems().get(1).getContainer().getLoadDy()).isEqualTo(2352);
		assertThat(plan.containerItems().get(1).getContainer().getLoadDz()).isEqualTo(2393);
		assertThat(plan.containerItems().get(1).getContainer().getMaxLoadWeight()).isEqualTo(26780_000);

		assertThat(plan.containerItems().get(2).getContainer().getId()).isEqualTo("C40HQ");
		assertThat(plan.containerItems().get(2).getContainer().getLoadDx()).isEqualTo(12032);
		assertThat(plan.containerItems().get(2).getContainer().getLoadDy()).isEqualTo(2352);
		assertThat(plan.containerItems().get(2).getContainer().getLoadDz()).isEqualTo(2698);
		assertThat(plan.containerItems().get(2).getContainer().getMaxLoadWeight()).isEqualTo(26600_000);

		assertThat(plan.containerItems().get(3).getContainer().getId()).isEqualTo("C45HQ");
		assertThat(plan.containerItems().get(3).getContainer().getLoadDx()).isEqualTo(13560);
		assertThat(plan.containerItems().get(3).getContainer().getLoadDy()).isEqualTo(2352);
		assertThat(plan.containerItems().get(3).getContainer().getLoadDz()).isEqualTo(2698);
		assertThat(plan.containerItems().get(3).getContainer().getMaxLoadWeight()).isEqualTo(27600_000);

		assertThat(plan.warnings()).containsExactly("UNSUPPORTED_CONTAINER_SKIPPED id=C45GP size=45 type=GP");
	}
}
