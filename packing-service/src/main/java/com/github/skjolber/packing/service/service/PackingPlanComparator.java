package com.github.skjolber.packing.service.service;

import java.util.Comparator;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.PackagerResult;

final class PackingPlanComparator implements Comparator<PackagerResult> {

	static final PackingPlanComparator INSTANCE = new PackingPlanComparator();

	private PackingPlanComparator() {
	}

	@Override
	public int compare(PackagerResult left, PackagerResult right) {
		if (left.size() != right.size()) {
			return Integer.compare(right.size(), left.size());
		}
		for (int i = 0; i < left.size(); i++) {
			Container leftContainer = left.get(i);
			Container rightContainer = right.get(i);
			int volume = Long.compare(leftContainer.getLoadVolume(), rightContainer.getLoadVolume());
			if (volume != 0) {
				return volume;
			}
			int count = Integer.compare(leftContainer.getStack().size(), rightContainer.getStack().size());
			if (count != 0) {
				return count;
			}
			int weight = Integer.compare(leftContainer.getLoadWeight(), rightContainer.getLoadWeight());
			if (weight != 0) {
				return weight;
			}
		}
		return 0;
	}
}
