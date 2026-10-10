package com.github.skjolber.packing.service.service.assignment;

import java.util.Locale;

public enum WholeOrderAssignmentStrategy {
	BALANCED,
	FILL_FIRST;

	public static WholeOrderAssignmentStrategy parse(String value) {
		if (value == null || value.isBlank()) return BALANCED;
		return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
			case "balanced" -> BALANCED;
			case "fill-first" -> FILL_FIRST;
			default -> throw new IllegalArgumentException(
					"Unknown packing assignment strategy: " + value
							+ "; expected balanced or fill-first");
		};
	}

	public String propertyValue() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
