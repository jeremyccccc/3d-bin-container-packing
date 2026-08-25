package com.github.skjolber.packing.service.service;

import java.util.List;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.service.dto.ContainerDto;

record PackingPlan(
		List<ContainerDto> requestedContainers,
		List<ContainerItem> containerItems,
		List<BoxItem> boxItems,
		List<CargoLine> cargoLines,
		List<String> warnings) {
}
