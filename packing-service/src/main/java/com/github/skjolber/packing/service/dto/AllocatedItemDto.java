package com.github.skjolber.packing.service.dto;

public record AllocatedItemDto(
		String inboundId,
		int num,
		double weight,
		double meas,
		SizeDto size) {
}
