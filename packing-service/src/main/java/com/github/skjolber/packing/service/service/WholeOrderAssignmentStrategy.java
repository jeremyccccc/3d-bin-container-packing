package com.github.skjolber.packing.service.service;

import java.util.Locale;

enum WholeOrderAssignmentStrategy {
	BALANCED,
	FILL_FIRST;

	static WholeOrderAssignmentStrategy parse(String value) {
		if (value == null || value.isBlank()) return BALANCED;
		return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
			case "balanced" -> BALANCED;
			case "fill-first" -> FILL_FIRST;
			default -> throw new IllegalArgumentException(
					"Unknown packing assignment strategy: " + value
							+ "; expected balanced or fill-first");
		};
	}

	String propertyValue() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
