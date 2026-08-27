package com.github.skjolber.packing.service.service;

import org.springframework.stereotype.Component;

import com.github.skjolber.packing.api.PackagerResult;

@Component
class PackingEngine {
	private static final long PACKING_BUDGET_MILLIS = 5_000L;
	private static final int MAX_OPTIMIZATION_CANDIDATES = 2_000;

	private final SingleContainerPackingOracle oracle = new SingleContainerPackingOracle();
	private final FrontLoadedPackingOptimizer optimizer = new FrontLoadedPackingOptimizer(oracle);

	PackagerResult pack(PackingPlan plan) {
		if (plan.containerItems().isEmpty() || plan.boxItems().isEmpty()) {
			return null;
		}

		OptimizationBudget budget = new OptimizationBudget(PACKING_BUDGET_MILLIS, MAX_OPTIMIZATION_CANDIDATES);
		PackagerResult initial = oracle.pack(plan.containerItems(), plan.boxItems(),
				plan.containerItems().stream().mapToInt(item -> item.getCount()).sum(), budget.deadline());
		return optimizer.optimize(plan, initial, budget);
	}
}
