package com.salon.salons.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchTest {

	private static final Instant NOW = Instant.parse("2026-11-03T09:00:00Z");

	private static final Address ADDRESS = new Address(34, "Kadıköy", null, "Moda Cad. 1", null);

	@Test
	void defaultsToIstanbulTime() {
		Branch branch = branch(new BranchDetails("Moda", null, ADDRESS, null, null, null));

		assertThat(branch.getTimeZone()).isEqualTo("Europe/Istanbul");
		assertThat(branch.hasLocation()).isFalse();
		assertThat(branch.getBookingPolicy()).isEqualTo(BookingPolicy.defaults());
	}

	@ParameterizedTest
	@ValueSource(strings = { "UTC+3", "+03:00", "Mars/Olympus", "GMT" })
	void acceptsOnlyRegionTimeZones(String timeZone) {
		assertThatThrownBy(() -> branch(new BranchDetails("Moda", null, ADDRESS, null, null, timeZone)))
			.isInstanceOf(ApiException.class)
			.extracting("code")
			.isEqualTo("INVALID_TIME_ZONE");
	}

	@Test
	void requiresBothCoordinatesOrNone() {
		assertThatThrownBy(() -> branch(new BranchDetails("Moda", null, ADDRESS, 40.99, null, null)))
			.isInstanceOf(ApiException.class)
			.extracting("code")
			.isEqualTo("INVALID_COORDINATES");
	}

	@Test
	void rejectsSlotIntervalsOffTheGrid() {
		BookingPolicy policy = new BookingPolicy(true, 7, 60, 60, 120, 120);

		assertThatThrownBy(policy::validated).isInstanceOf(ApiException.class)
			.extracting("code")
			.isEqualTo("INVALID_BOOKING_POLICY");
	}

	private static Branch branch(BranchDetails details) {
		return new Branch(UUID.randomUUID(), "moda", details, BookingPolicy.defaults(), NOW);
	}

}
