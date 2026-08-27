package com.github.skjolber.packing.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PackingRuleDto(
		@JsonProperty("CustomerMode") int customerMode,
		@JsonProperty("HeightPosition") int heightPosition,
		@JsonProperty("DoorSide") boolean doorSide,
		@JsonProperty("Method") int method,
		@JsonProperty("FlatHeight") Double flatHeight) {

	public static PackingRuleDto none() {
		return new PackingRuleDto(0, 0, false, 0, null);
	}
}
