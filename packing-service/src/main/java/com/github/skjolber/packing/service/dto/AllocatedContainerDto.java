package com.github.skjolber.packing.service.dto;

import java.util.List;

public record AllocatedContainerDto(
		String id,
		int size,
		String type,
		List<AllocatedHouseBillDto> allocatedHouseBillList) {
}
