package com.github.skjolber.packing.service.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.skjolber.packing.service.dto.PackingRequest;
import com.github.skjolber.packing.service.dto.PackingResponse;
import com.github.skjolber.packing.service.service.PackingService;

@RestController
@RequestMapping("/api/packing")
public class PackingController {

	private final PackingService packingService;

	public PackingController(PackingService packingService) {
		this.packingService = packingService;
	}

	@PostMapping("/pack")
	public PackingResponse pack(@RequestBody PackingRequest request) {
		return packingService.pack(request);
	}
}
