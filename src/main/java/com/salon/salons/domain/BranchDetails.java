package com.salon.salons.domain;

/**
 * Editable branch fields. {@code timeZone} defaults to {@value Branch#DEFAULT_TIME_ZONE} when blank.
 */
public record BranchDetails(String name, String phone, Address address, Double latitude, Double longitude,
		String timeZone) {
}
