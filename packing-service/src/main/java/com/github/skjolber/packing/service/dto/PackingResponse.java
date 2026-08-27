package com.github.skjolber.packing.service.dto;

import java.util.List;

public record PackingResponse(
		String masterBsId,
		boolean success,
		String message,
		List<String> warnings,
		List<AllocatedContainerDto> containerLists) {
}
