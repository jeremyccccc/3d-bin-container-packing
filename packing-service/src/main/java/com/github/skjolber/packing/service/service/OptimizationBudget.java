package com.github.skjolber.packing.service.service;

final class OptimizationBudget {

	private final long deadline;
	private final int maxCandidates;
	private int evaluatedCandidates;

	OptimizationBudget(long durationMillis, int maxCandidates) {
		this.deadline = System.currentTimeMillis() + durationMillis;
		this.maxCandidates = maxCandidates;
	}

	long deadline() {
		return deadline;
	}

	boolean tryAcquireCandidate() {
		if (isExpired() || evaluatedCandidates >= maxCandidates) {
			return false;
		}
		evaluatedCandidates++;
		return true;
	}

	boolean isExpired() {
		return System.currentTimeMillis() >= deadline;
	}

	int evaluatedCandidates() {
		return evaluatedCandidates;
	}
}
