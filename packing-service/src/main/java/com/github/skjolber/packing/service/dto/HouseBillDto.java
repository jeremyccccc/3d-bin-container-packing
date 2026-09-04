package com.github.skjolber.packing.service.dto;

import java.util.List;

public record HouseBillDto(
		String houseBsId,
		String desc,
		String customer,
		PackingRuleDto rule,
		int totalNum,
		double totalWeight,
		double totalMeas,
		List<HouseBillItemDto> items) {
}
