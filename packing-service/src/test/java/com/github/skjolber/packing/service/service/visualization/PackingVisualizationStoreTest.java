package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.service.service.mapping.PackingMapper;
import com.github.skjolber.packing.service.service.visualization.PackingVisualizationStore;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;

class PackingVisualizationStoreTest {

	@TempDir
	Path tempDir;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void savesVisualizationForSuccessfulPacking() throws Exception {
		PackingVisualizationStore store = new PackingVisualizationStore(objectMapper, tempDir);
		PackingService service = new PackingService(new PackingMapper(), new PackingEngine(), store);

		PackingResponse response = service.pack(request(), "https://www.pstarlinker.com:811");

		assertThat(response.success()).isTrue();
		assertThat(response.resultId()).startsWith("pack_");
		assertThat(response.viewerUrl()).isEqualTo("https://www.pstarlinker.com:811/packing-viewer/" + response.resultId());
		Path saved = tempDir.resolve(response.resultId() + ".json");
		assertThat(saved).isRegularFile();

		JsonNode savedRoot = objectMapper.readTree(saved.toFile());
		assertThat(savedRoot.path("masterBsId").asText()).isEqualTo("M1");
		assertThat(savedRoot.path("masterNo").asText()).isEqualTo("MBL-001");
		assertThat(savedRoot.path("request").path("masterBsId").asText()).isEqualTo("M1");
		assertThat(savedRoot.path("response").path("resultId").asText()).isEqualTo(response.resultId());

		JsonNode visualization = store.findVisualization(response.resultId()).orElseThrow();
		assertThat(visualization.path("masterNo").asText()).isEqualTo("MBL-001");
		assertThat(visualization.path("containers")).hasSize(1);
		assertThat(visualization.path("containers").get(0).path("stack").path("placements")).hasSize(1);
		JsonNode placement = visualization.path("containers").get(0)
				.path("stack").path("placements").get(0);
		assertThat(placement.path("houseBsId").asText()).isEqualTo("H1");
		assertThat(placement.path("houseBsNo").asText()).isEqualTo("HBL-001");
		assertThat(placement.path("inboundId").asText()).isEqualTo("I1");
		assertThat(placement.path("inboundNo").asText()).isEqualTo("IN-001");
		assertThat(placement.path("customer").asText()).isEqualTo("ACME");
		assertThat(placement.path("description").asText()).isEqualTo("cargo");
		assertThat(placement.path("unitWeightKg").asDouble()).isEqualTo(1.0);
		assertThat(placement.path("unitVolumeM3").asDouble()).isEqualTo(0.001);
		assertThat(placement.path("originalDx").asInt()).isEqualTo(100);
		assertThat(placement.path("originalDy").asInt()).isEqualTo(100);
		assertThat(placement.path("originalDz").asInt()).isEqualTo(100);
		assertThat(placement.path("heightPosition").asInt()).isZero();
		assertThat(placement.path("noPress").asBoolean()).isFalse();
		assertThat(placement.path("doorSide").asBoolean()).isFalse();
		assertThat(placement.path("stackable").path("name").asText()).isEqualTo("HBL-001 IN-001");
		assertThat(placement.path("stackable").path("id").asText())
				.endsWith("|1");
	}

	@Test
	void rejectsUnsafeResultIds() throws Exception {
		PackingVisualizationStore store = new PackingVisualizationStore(objectMapper, tempDir);

		assertThat(store.findVisualization("../secret")).isEmpty();
		assertThat(Files.list(tempDir)).isEmpty();
	}

	private PackingRequest request() throws Exception {
		return objectMapper.readValue("""
				{
				  "masterBsId":"M1",
				  "masterNo":"MBL-001",
				  "containerLists":[{"id":"C1","size":40,"type":"HQ"}],
				  "houseBillList":[{
				    "houseBsId":"H1",
				    "houseBsNo":"HBL-001",
				    "desc":"cargo",
				    "customer":"ACME",
				    "rule":{"CustomerMode":0,"HeightPosition":0,"DoorSide":false,"Method":0,"FlatHeight":null},
				    "totalNum":1,
				    "totalWeight":1,
				    "totalMeas":0.001,
				    "items":[{
				      "inboundId":"I1",
				      "inboundNo":"IN-001",
				      "num":1,
				      "weight":1,
				      "meas":0.001,
				      "size":{"length":10,"width":10,"height":10}
				    }]
				  }]
				}
				""", PackingRequest.class);
	}
}
