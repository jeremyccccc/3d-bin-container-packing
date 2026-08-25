package com.github.skjolber.packing.service.dto;

import java.util.List;

public record PackingResponse(
		String masterBsId,
		List<AllocatedContainerDto> containerLists) {
}
