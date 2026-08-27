package com.github.skjolber.packing.service.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OptimizationBudgetTest {

	@Test
	void stopsAtCandidateLimit() {
		OptimizationBudget budget = new OptimizationBudget(10_000, 2);

		assertThat(budget.tryAcquireCandidate()).isTrue();
		assertThat(budget.tryAcquireCandidate()).isTrue();
		assertThat(budget.tryAcquireCandidate()).isFalse();
		assertThat(budget.evaluatedCandidates()).isEqualTo(2);
	}

	@Test
	void stopsAfterDeadline() throws Exception {
		OptimizationBudget budget = new OptimizationBudget(1, 100);
		Thread.sleep(5);

		assertThat(budget.tryAcquireCandidate()).isFalse();
	}
}
