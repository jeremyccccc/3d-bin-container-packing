package com.github.skjolber.packing.service.service.rule;

import com.github.skjolber.packing.service.service.PackingPlan;

public final class PackingBusinessRules {
	private PackingBusinessRules() {
	}

	public static boolean hasAny(PackingPlan plan) {
		return plan.boxItems().stream().anyMatch(item ->
				BottomRuleSupport.hasBottomRule(item.getBox())
						|| NoPressRuleSupport.hasNoPressRule(item.getBox())
						|| DoorSideRuleSupport.hasDoorSideRule(item.getBox()));
	}
}
