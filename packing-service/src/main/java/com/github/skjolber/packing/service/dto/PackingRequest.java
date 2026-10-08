package com.github.skjolber.packing.service.dto;

import java.util.List;

public record PackingRequest(
		String masterBsId,
		String masterNo,
		List<ContainerDto> containerLists,
		List<HouseBillDto> houseBillList) {

	public PackingRequest(String masterBsId, List<ContainerDto> containerLists,
			List<HouseBillDto> houseBillList) {
		this(masterBsId, null, containerLists, houseBillList);
	}
}
