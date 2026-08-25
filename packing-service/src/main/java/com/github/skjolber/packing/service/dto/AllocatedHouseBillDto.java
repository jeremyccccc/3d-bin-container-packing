package com.github.skjolber.packing.service.dto;

import java.util.List;

public record AllocatedHouseBillDto(
		String houseBsId,
		List<AllocatedItemDto> items) {
}
