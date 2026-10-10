package com.github.skjolber.packing.service.service.assignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.skjolber.packing.api.BoxItem;
import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.service.service.mapping.PackingMapper;

final class WholeOrderAssignmentSupport {

	private WholeOrderAssignmentSupport() {
	}

	static List<Container> expandContainers(List<ContainerItem> containerItems) {
		List<Container> result = new ArrayList<>();
		for (ContainerItem item : containerItems) {
			for (int i = 0; i < item.getCount(); i++) result.add(item.getContainer());
		}
		return result;
	}

	static List<WholeOrderGroup> group(List<BoxItem> boxItems) {
		Map<String, List<BoxItem>> byHouseBill = new LinkedHashMap<>();
		int anonymous = 0;
		for (BoxItem item : boxItems) {
			String houseBsId = item.getBox().getProperty(PackingMapper.PROP_HOUSE_BS_ID);
			if (houseBsId == null || houseBsId.isBlank()) houseBsId = "__anonymous_" + anonymous++;
			byHouseBill.computeIfAbsent(houseBsId, ignored -> new ArrayList<>()).add(item);
		}
		List<WholeOrderGroup> result = new ArrayList<>();
		for (Map.Entry<String, List<BoxItem>> entry : byHouseBill.entrySet()) {
			List<BoxItem> items = List.copyOf(entry.getValue());
			long volume = items.stream().mapToLong(BoxItem::getVolume).sum();
			long weight = items.stream().mapToLong(BoxItem::getWeight).sum();
			long largestBox = items.stream().mapToLong(item -> item.getBox().getVolume()).max().orElse(0L);
			String stableKey = entry.getKey() + '\u0000' + items.stream()
					.map(PackingDeterminism::boxItemKey).sorted()
					.reduce((left, right) -> left + '\u0001' + right).orElse("");
			result.add(new WholeOrderGroup(entry.getKey(), items, volume, weight, largestBox, stableKey));
		}
		result.sort(Comparator.comparing(WholeOrderGroup::stableKey));
		return result;
	}

	static long[] loads(List<WholeOrderGroup> groups, int[] assignment,
			int containerCount, boolean volume) {
		long[] result = new long[containerCount];
		for (int i = 0; i < groups.size(); i++) {
			result[assignment[i]] += volume ? groups.get(i).volume() : groups.get(i).weight();
		}
		return result;
	}

	static boolean fits(WholeOrderGroup group, Container container, long usedVolume, long usedWeight) {
		return withinCapacity(group, container,
				usedVolume + group.volume(), usedWeight + group.weight());
	}

	static boolean withinCapacity(WholeOrderGroup group, Container container,
			long totalVolume, long totalWeight) {
		if (totalVolume > container.getMaxLoadVolume() || totalWeight > container.getMaxLoadWeight()) return false;
		return group.items().stream().allMatch(item -> container.canLoad(item.getBox()));
	}

	static void addCandidate(List<WholeOrderAssignmentSolver.Candidate> result, Set<String> signatures,
			List<WholeOrderGroup> groups, List<Container> containers, int[] assignment, String source) {
		String signature = canonicalSignature(groups, assignment, containers);
		if (!signatures.add(signature)) return;
		List<List<BoxItem>> itemsByContainer = new ArrayList<>(containers.size());
		for (int i = 0; i < containers.size(); i++) itemsByContainer.add(new ArrayList<>());
		for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
			itemsByContainer.get(assignment[groupIndex]).addAll(groups.get(groupIndex).items());
		}
		result.add(new WholeOrderAssignmentSolver.Candidate(
				itemsByContainer.stream().map(List::copyOf).toList(), source,
				result.size(), signature, null));
	}

	private static String canonicalSignature(List<WholeOrderGroup> groups, int[] assignment,
			List<Container> containers) {
		List<List<String>> groupKeysByContainer = new ArrayList<>(containers.size());
		for (int i = 0; i < containers.size(); i++) groupKeysByContainer.add(new ArrayList<>());
		for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
			groupKeysByContainer.get(assignment[groupIndex]).add(groups.get(groupIndex).stableKey());
		}
		List<String> loads = new ArrayList<>(containers.size());
		for (int containerIndex = 0; containerIndex < containers.size(); containerIndex++) {
			List<String> keys = groupKeysByContainer.get(containerIndex);
			keys.sort(String::compareTo);
			loads.add(containerType(containers.get(containerIndex)) + '[' + String.join(";", keys) + ']');
		}
		loads.sort(String::compareTo);
		return String.join("|", loads);
	}

	private static String containerType(Container container) {
		return container.getDx() + "x" + container.getDy() + "x" + container.getDz()
				+ '/' + container.getLoadDx() + "x" + container.getLoadDy() + "x" + container.getLoadDz()
				+ ':' + container.getEmptyWeight() + ':' + container.getMaxLoadWeight();
	}
}
