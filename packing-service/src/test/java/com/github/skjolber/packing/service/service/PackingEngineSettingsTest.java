package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.service.service.assignment.WholeOrderAssignmentStrategy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PackingEngineSettingsTest {

	@Test
	void rejectsInvalidTargetFillRatio() {
		assertThatThrownBy(() -> settings(0.0, 1, 8, 8))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("target-fill-ratio");
	}

	@Test
	void rejectsInvalidParallelism() {
		assertThatThrownBy(() -> settings(0.85, 17, 8, 8))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("parallelism");
	}

	@Test
	void rejectsInvalidProbeBudgetEvenWhenProbeIsDisabled() {
		assertThatThrownBy(() -> settings(0.85, 1, 0, 8))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidate probe");
	}

	private static PackingEngineSettings settings(double targetFillRatio, int parallelism,
			int probeWindow, int probeStates) {
		return new PackingEngineSettings(false, true, WholeOrderAssignmentStrategy.FILL_FIRST,
				targetFillRatio, true, 60_000L, 32, 32, parallelism,
				false, probeWindow, probeStates);
	}
}
