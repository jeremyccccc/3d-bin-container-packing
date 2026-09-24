package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;

class AutomaticContainerFallbackTest {

	@TempDir
	Path tempDir;

	@Test
	void addsA40HqAsTheFourthContainer() throws Exception {
		PackingPlan original = new PackingMapper().toPlan(request());

		PackingPlan fallback = PackingEngine.withAutomatic40Hq(original);

		assertThat(fallback.requestedContainers()).hasSize(4);
		assertThat(fallback.requestedContainers().get(3).size()).isEqualTo(40);
		assertThat(fallback.requestedContainers().get(3).type()).isEqualTo("HQ");
		assertThat(fallback.containerItems().get(3).getContainer().getDescription()).isEqualTo("40HQ");
		assertThat(fallback.warnings()).anyMatch(value -> value.startsWith("AUTO_40HQ_FALLBACK"));
	}

	@Test
	void addsOne40HqForAnyOriginalContainerCount() throws Exception {
		PackingRequest singleContainerRequest = new PackingRequest(request().masterBsId(),
				List.of(request().containerLists().get(0)), request().houseBillList());
		PackingPlan original = new PackingMapper().toPlan(singleContainerRequest);

		PackingPlan fallback = PackingEngine.withAutomatic40Hq(original);

		assertThat(fallback.requestedContainers()).hasSize(2);
		assertThat(fallback.requestedContainers().get(1).id()).isEqualTo("AUTO-40HQ-2");
		assertThat(fallback.requestedContainers().get(1).size()).isEqualTo(40);
		assertThat(fallback.requestedContainers().get(1).type()).isEqualTo("HQ");
		assertThat(fallback.warnings()).contains(
				"AUTO_40HQ_FALLBACK targetContainers=1 addedContainerId=AUTO-40HQ-2");
	}

	@Test
	void preservesTheFourContainerViewerWhenTheThreeContainerTargetIsNotAchieved() throws Exception {
		PackingMapper mapper = new PackingMapper();
		PackingPlan fallback = PackingEngine.withAutomatic40Hq(mapper.toPlan(request()));
		List<Container> containers = new ArrayList<>();
		for (var item : fallback.containerItems()) containers.add(item.getContainer());
		PackagerResult fourContainerLayout = new PackagerResult(containers, 1L, false);
		PackingOutcome outcome = new PackingOutcome(fallback, fourContainerLayout, false, true, 3);
		PackingVisualizationStore store = new PackingVisualizationStore(new ObjectMapper(), tempDir);
		PackingService service = new PackingService(mapper, new StubPackingEngine(outcome), store);

		PackingResponse response = service.pack(request(), "https://viewer.example");

		assertThat(response.success()).isFalse();
		assertThat(response.message()).isEqualTo("装箱失败：可查看4柜布局");
		assertThat(response.containerLists()).hasSize(4);
		assertThat(response.containerLists().get(3).id()).startsWith("AUTO-40HQ-4");
		assertThat(response.resultId()).startsWith("pack_");
		assertThat(response.viewerUrl()).isEqualTo(
				"https://viewer.example/packing-viewer/" + response.resultId());
		JsonNode visualization = store.findVisualization(response.resultId()).orElseThrow();
		assertThat(visualization.path("containers")).hasSize(4);
	}

	@Test
	void returnsOnlyTheThreeActuallyUsedContainersAfterSuccessfulCompaction() throws Exception {
		PackingMapper mapper = new PackingMapper();
		PackingPlan fallback = PackingEngine.withAutomatic40Hq(mapper.toPlan(request()));
		List<Container> compactedContainers = fallback.containerItems().stream().limit(3)
				.map(item -> item.getContainer()).toList();
		PackagerResult compactedLayout = new PackagerResult(compactedContainers, 1L, false);
		PackingOutcome outcome = new PackingOutcome(fallback, compactedLayout, true, true, 3);
		PackingService service = new PackingService(mapper, new StubPackingEngine(outcome));

		PackingResponse response = service.pack(request());

		assertThat(response.success()).isTrue();
		assertThat(response.containerLists()).extracting(container -> container.id())
				.containsExactly("C1", "C2", "C3");
	}

	@Test
	void skipsTailCompactionWhenFallbackAlreadyUsesTheTargetContainerCount() throws Exception {
		PackingPlan fallback = PackingEngine.withAutomatic40Hq(new PackingMapper().toPlan(request()));
		List<Container> threeContainers = fallback.containerItems().stream().limit(3)
				.map(item -> item.getContainer()).toList();
		List<Container> fourContainers = fallback.containerItems().stream()
				.map(item -> item.getContainer()).toList();

		assertThat(PackingEngine.shouldCompactTail(true, true,
				new PackagerResult(threeContainers, 1L, false), 3)).isFalse();
		assertThat(PackingEngine.shouldCompactTail(true, true,
				new PackagerResult(fourContainers, 1L, false), 3)).isTrue();
	}

	private static PackingRequest request() throws Exception {
		return new ObjectMapper().readValue("""
				{
				  "masterBsId":"M-AUTO",
				  "containerLists":[
				    {"id":"C1","size":20,"type":"GP"},
				    {"id":"C2","size":40,"type":"GP"},
				    {"id":"C3","size":45,"type":"HQ"}
				  ],
				  "houseBillList":[{
				    "houseBsId":"H1","totalNum":1,"totalWeight":1,"totalMeas":1,
				    "items":[{"inboundId":"I1","num":1,"weight":1,"meas":1,
				      "size":{"length":100,"width":100,"height":100}}]
				  }]
				}
				""", PackingRequest.class);
	}

	private static final class StubPackingEngine extends PackingEngine {
		private final PackingOutcome outcome;

		private StubPackingEngine(PackingOutcome outcome) {
			this.outcome = outcome;
		}

		@Override
		PackingOutcome packOutcome(PackingPlan plan) {
			return outcome;
		}
	}
}
