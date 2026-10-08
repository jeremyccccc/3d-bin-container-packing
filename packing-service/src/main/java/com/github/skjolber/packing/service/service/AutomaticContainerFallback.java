package com.github.skjolber.packing.service.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.dto.ContainerDto;

/** Builds the extra 40HQ fallback plan and decides whether it needs compaction. */
final class AutomaticContainerFallback {

	private AutomaticContainerFallback() {
	}

	static PackingPlan add40Hq(PackingPlan plan) {
		Set<String> ids = new HashSet<>();
		for (ContainerDto container : plan.requestedContainers()) ids.add(container.id());
		int targetContainerCount = totalContainerCount(plan.containerItems());
		String baseId = "AUTO-40HQ-" + (targetContainerCount + 1);
		String id = baseId;
		for (int suffix = 2; ids.contains(id); suffix++) id = baseId + "-" + suffix;
		ContainerDto automatic = new ContainerDto(id, 40, "HQ");
		ContainerItem mapped = PackingMapper.toContainerItem(automatic);
		if (mapped == null) throw new IllegalStateException("无法创建自动40HQ柜型");

		List<ContainerDto> requested = new ArrayList<>(plan.requestedContainers());
		requested.add(automatic);
		List<ContainerItem> containers = new ArrayList<>(plan.containerItems());
		containers.add(mapped);
		List<String> warnings = new ArrayList<>(plan.warnings());
		warnings.add("AUTO_40HQ_FALLBACK targetContainers=" + targetContainerCount
				+ " addedContainerId=" + id);
		return new PackingPlan(List.copyOf(requested), List.copyOf(containers), plan.boxItems(),
				plan.cargoLines(), List.copyOf(warnings));
	}

	static boolean shouldCompact(boolean compactTail, boolean tailCompactionEnabled,
			PackagerResult result, int targetContainerCount) {
		return compactTail && tailCompactionEnabled && result != null && result.isSuccess()
				&& targetContainerCount > 0 && result.size() > targetContainerCount;
	}

	private static int totalContainerCount(List<ContainerItem> containerItems) {
		int count = 0;
		for (ContainerItem item : containerItems) count += item.getCount();
		return count;
	}
}
