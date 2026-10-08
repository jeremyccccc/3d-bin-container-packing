package com.github.skjolber.packing.service.service;

record PackingLogContext(long searchId, int attempt, String source,
		int containerIndex, int containerCount, String containerId) {

	static final PackingLogContext NONE = new PackingLogContext(0L, 0, "", 0, 0, "");

	String fields() {
		if (searchId == 0L) return "";
		return " searchId=" + searchId + " attempt=" + attempt
				+ " source=" + PackingDiagnostics.safe(source)
				+ " container=" + containerIndex + "/" + containerCount
				+ " containerId=" + PackingDiagnostics.safe(containerId);
	}
}
