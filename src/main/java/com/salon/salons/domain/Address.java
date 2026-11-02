package com.salon.salons.domain;

import jakarta.persistence.Embeddable;

/**
 * A Turkish postal address. {@code provinceId} is the province's licence-plate code (1–81).
 */
@Embeddable
public record Address(Integer provinceId, String district, String neighbourhood, String addressLine,
		String postalCode) {
}
