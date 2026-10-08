package com.github.skjolber.packing.service.service;

import com.github.skjolber.packing.api.PackagerResult;

record PackingOutcome(
		PackingPlan plan,
		PackagerResult result,
		boolean targetAchieved,
		boolean fallbackUsed,
		int targetContainerCount) {
}
