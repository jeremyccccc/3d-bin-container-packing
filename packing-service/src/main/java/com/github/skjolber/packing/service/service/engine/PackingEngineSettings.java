package com.github.skjolber.packing.service.service.engine;

import java.util.Objects;

import com.github.skjolber.packing.service.service.assignment.WholeOrderAssignmentStrategy;

record PackingEngineSettings(
		boolean diagnosticsEnabled,
		boolean blockBeamOnly,
		WholeOrderAssignmentStrategy assignmentStrategy,
		double assignmentTargetFillRatio,
		boolean tailCompactionEnabled,
		long tailCompactionSearchMillis,
		int tailCompactionBeamWidth,
		int tailCompactionBranching,
		int wholeOrderParallelism,
		boolean candidateProbeEnabled,
		int candidateProbeWindow,
		int candidateProbeStates) {

	PackingEngineSettings {
		Objects.requireNonNull(assignmentStrategy, "assignmentStrategy");
		if (assignmentTargetFillRatio <= 0.0 || assignmentTargetFillRatio > 1.0) {
			throw new IllegalArgumentException("packing.assignment.target-fill-ratio must be in (0, 1]");
		}
		if (tailCompactionSearchMillis <= 0L || tailCompactionBeamWidth <= 0 || tailCompactionBranching <= 0) {
			throw new IllegalArgumentException("tail compaction search settings must be positive");
		}
		if (wholeOrderParallelism <= 0 || wholeOrderParallelism > 16) {
			throw new IllegalArgumentException("packing.whole-order.parallelism must be in [1, 16]");
		}
		if (candidateProbeWindow <= 0 || candidateProbeStates <= 0) {
			throw new IllegalArgumentException("candidate probe settings must be positive");
		}
	}
}
