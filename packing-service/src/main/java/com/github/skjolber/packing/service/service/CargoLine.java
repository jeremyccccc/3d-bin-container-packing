package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.service.dto.HouseBillItemDto;

record CargoLine(
		String cargoId,
		String houseBsId,
		String desc,
		int heightPosition,
		boolean noPress,
		HouseBillItemDto item,
		int calculatedQuantity,
		int scaledLength,
		int scaledWidth,
		int scaledHeight,
		int scaledUnitWeight) {
}
