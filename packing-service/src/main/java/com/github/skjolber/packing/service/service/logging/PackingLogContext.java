package com.github.skjolber.packing.service.service.logging;

public record PackingLogContext(long searchId, int attempt, String source,
		int containerIndex, int containerCount, String containerId) {

	public static final PackingLogContext NONE = new PackingLogContext(0L, 0, "", 0, 0, "");

	public String fields() {
		if (searchId == 0L) return "";
		return " searchId=" + searchId + " attempt=" + attempt
				+ " source=" + PackingDiagnostics.safe(source)
				+ " container=" + containerIndex + "/" + containerCount
				+ " containerId=" + PackingDiagnostics.safe(containerId);
	}
}
