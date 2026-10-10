package com.github.skjolber.packing.service.service.logging;

import java.util.HashMap;
import java.util.Map;

/** Lightweight per-thread counters for one packing strategy. */
public final class PackingSearchDiagnostics {

	private static final ThreadLocal<Counters> CURRENT = new ThreadLocal<>();

	private PackingSearchDiagnostics() {
	}

	public static void begin(boolean enabled) {
		if (enabled) {
			CURRENT.set(new Counters());
		} else {
			CURRENT.remove();
		}
	}

	public static void candidate() {
		Counters counters = CURRENT.get();
		if (counters != null) {
			counters.candidates++;
		}
	}

	public static void rejected(String reason) {
		Counters counters = CURRENT.get();
		if (counters != null) {
			counters.rejections.merge(reason, 1L, Long::sum);
		}
	}

	public static Counters finish() {
		Counters counters = CURRENT.get();
		CURRENT.remove();
		return counters != null ? counters : new Counters();
	}

	public static final class Counters {
		private long candidates;
		private final Map<String, Long> rejections = new HashMap<>();

		public long candidates() {
			return candidates;
		}

		public Map<String, Long> rejections() {
			return Map.copyOf(rejections);
		}
	}
}
