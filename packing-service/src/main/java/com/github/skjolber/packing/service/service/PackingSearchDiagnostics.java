package com.github.skjolber.packing.service.service;

import java.util.HashMap;
import java.util.Map;

/** Lightweight per-thread counters for one packing strategy. */
final class PackingSearchDiagnostics {

	private static final ThreadLocal<Counters> CURRENT = new ThreadLocal<>();

	private PackingSearchDiagnostics() {
	}

	static void begin(boolean enabled) {
		if (enabled) {
			CURRENT.set(new Counters());
		} else {
			CURRENT.remove();
		}
	}

	static void candidate() {
		Counters counters = CURRENT.get();
		if (counters != null) {
			counters.candidates++;
		}
	}

	static void rejected(String reason) {
		Counters counters = CURRENT.get();
		if (counters != null) {
			counters.rejections.merge(reason, 1L, Long::sum);
		}
	}

	static Counters finish() {
		Counters counters = CURRENT.get();
		CURRENT.remove();
		return counters != null ? counters : new Counters();
	}

	static final class Counters {
		private long candidates;
		private final Map<String, Long> rejections = new HashMap<>();

		long candidates() {
			return candidates;
		}

		Map<String, Long> rejections() {
			return Map.copyOf(rejections);
		}
	}
}
