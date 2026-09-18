package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.skjolber.packing.service.dto.PackingRequest;

class PackingEngineDiagnosticsTest {

	@Test
	void logsWholeOrderCandidateContainerAndAlgorithmTimings() throws Exception {
		PackingRequest request = new ObjectMapper().readValue("""
				{
				  "masterBsId":"M-DIAGNOSTIC",
				  "containerLists":[
				    {"id":"C1","size":20,"type":"GP"},
				    {"id":"C2","size":20,"type":"GP"}
				  ],
				  "houseBillList":[
				    {"houseBsId":"H1","totalNum":1,"totalWeight":10,"totalMeas":1,
				     "items":[{"inboundId":"I1","num":1,"weight":10,"meas":1,
				       "size":{"length":100,"width":100,"height":100}}]},
				    {"houseBsId":"H2","totalNum":1,"totalWeight":10,"totalMeas":1,
				     "items":[{"inboundId":"I2","num":1,"weight":10,"meas":1,
				       "size":{"length":100,"width":100,"height":100}}]}
				  ]
				}
				""", PackingRequest.class);

		PrintStream original = System.out;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
			System.setOut(capture);
			new PackingService(new PackingMapper(),
					new PackingEngine(PlacementSupport.DEFAULT_POLICY, true)).pack(request);
		} finally {
			System.setOut(original);
		}

		String log = output.toString(StandardCharsets.UTF_8);
		assertThat(log).contains(
				"whole-order search-start searchId=",
				"candidateGenerationMs=",
				"whole-order candidate-start searchId=",
				"whole-order container-start searchId=",
				"houseBillIds=",
				"BlockBeam searchId=",
				"whole-order container-end searchId=",
				"whole-order candidate-end searchId=",
				"whole-order cache-miss searchId=",
				"termination=");
		assertThat(log).containsPattern("cacheHits=[1-9][0-9]*");
	}
}
