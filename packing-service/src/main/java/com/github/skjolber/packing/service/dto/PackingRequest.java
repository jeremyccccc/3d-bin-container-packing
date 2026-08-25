package com.github.skjolber.packing.service.dto;

import java.util.List;

public record PackingRequest(
		String masterBsId,
		List<ContainerDto> containerLists,
		List<HouseBillDto> houseBillList) {
}
