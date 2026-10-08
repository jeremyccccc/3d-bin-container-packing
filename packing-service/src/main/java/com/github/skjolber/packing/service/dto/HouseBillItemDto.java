package com.github.skjolber.packing.service.dto;

public record HouseBillItemDto(
		String inboundId,
		String inboundNo,
		int num,
		SizeDto size,
		double weight,
		double meas) {
}
