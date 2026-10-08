package com.github.skjolber.packing.service.service;

final class PackingBusinessRules {
	private PackingBusinessRules() {
	}

	static boolean hasAny(PackingPlan plan) {
		return plan.boxItems().stream().anyMatch(item ->
				BottomRuleSupport.hasBottomRule(item.getBox())
						|| NoPressRuleSupport.hasNoPressRule(item.getBox())
						|| DoorSideRuleSupport.hasDoorSideRule(item.getBox()));
	}
}
