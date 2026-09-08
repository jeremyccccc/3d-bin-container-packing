package com.github.skjolber.packing.service.api;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;
import com.github.skjolber.packing.service.service.PackingService;
import com.github.skjolber.packing.service.service.PackingRequestLogStore;
import com.github.skjolber.packing.service.service.PackingVisualizationStore;

@RestController
@RequestMapping("/api/packing")
public class PackingController {

	private final PackingService packingService;
	private final PackingVisualizationStore visualizationStore;
	private final PackingRequestLogStore requestLogStore;
	private final String publicBaseUrl;

	public PackingController(PackingService packingService,
			PackingVisualizationStore visualizationStore,
			PackingRequestLogStore requestLogStore,
			@Value("${packing.public-base-url:https://www.pstarlinker.com:811}") String publicBaseUrl) {
		this.packingService = packingService;
		this.visualizationStore = visualizationStore;
		this.requestLogStore = requestLogStore;
		this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
	}

	@PostMapping("/pack")
	public PackingResponse pack(@RequestBody PackingRequest request) {
		try {
			requestLogStore.save(request);
		} catch (IOException e) {
			// Logging failure must not prevent the packing request from being processed.
			System.err.println("Unable to save packing request log: " + e.getMessage());
		}
		return packingService.pack(request, publicBaseUrl);
	}

	@GetMapping("/results/{resultId}/visualization")
	public ResponseEntity<JsonNode> visualization(@PathVariable("resultId") String resultId) throws IOException {
		return visualizationStore.findVisualization(resultId)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

}
