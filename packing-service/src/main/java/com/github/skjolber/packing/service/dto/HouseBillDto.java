package com.github.skjolber.packing.service.dto;

import java.util.List;
import java.util.Map;

public record HouseBillDto(
		String houseBsId,
		String desc,
		Map<String, Object> rule,
		int totalNum,
		double totalWeight,
		double totalMeas,
		List<HouseBillItemDto> items) {
}
