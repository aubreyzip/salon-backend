package com.salon.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What booking a set of services at one branch takes (ADR-0012): the services with their branch
 * prices and durations, the buffer blocked after the whole booking, and the staff members able to
 * perform all of the services with their total duration.
 *
 * @param services the requested services, in request order
 * @param bufferMinutes the largest buffer among the services, applied once after the booking
 * @param durationMinutesByStaff total duration per qualified staff member (staff skill override,
 * else branch override, else service default, summed over the services). Staff are not yet filtered
 * by status, bookability or branch assignment.
 */
public record BookingRequirements(List<RequiredService> services, int bufferMinutes,
		Map<UUID, Integer> durationMinutesByStaff) {

	public BookingRequirements {
		services = List.copyOf(services);
		durationMinutesByStaff = Map.copyOf(durationMinutesByStaff);
	}

	public Optional<Integer> durationMinutesFor(UUID staffId) {
		return Optional.ofNullable(durationMinutesByStaff.get(staffId));
	}

	/**
	 * A requested service at the branch, with branch overrides applied.
	 *
	 * @param priceFrom the price is a starting price
	 */
	public record RequiredService(UUID serviceId, String name, int durationMinutes, int bufferMinutes,
			BigDecimal price, String currency, boolean priceFrom) {
	}

}
