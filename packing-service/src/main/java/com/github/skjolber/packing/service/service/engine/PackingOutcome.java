package com.github.skjolber.packing.service.service.engine;

import com.github.skjolber.packing.api.PackagerResult;
import com.github.skjolber.packing.service.service.PackingPlan;

public record PackingOutcome(
		PackingPlan plan,
		PackagerResult result,
		boolean targetAchieved,
		boolean fallbackUsed,
		int targetContainerCount) {
}
