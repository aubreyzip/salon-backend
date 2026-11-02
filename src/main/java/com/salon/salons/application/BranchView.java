package com.salon.salons.application;

import java.util.UUID;

import com.salon.salons.domain.BookingPolicy;

public record BranchView(UUID id, UUID salonId, String name, String slug, String phone, AddressView address,
		Double latitude, Double longitude, String timeZone, boolean active, BookingPolicy bookingPolicy) {

	public record AddressView(int provinceId, String provinceName, String district, String neighbourhood,
			String addressLine, String postalCode) {
	}

}
