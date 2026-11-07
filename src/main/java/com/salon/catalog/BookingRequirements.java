package com.salon.catalog;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What booking a set of services at one branch takes (ADR-0012): the services with their branch
 * prices and durations, the buffer blocked after the whole booking, and the staff members able to
 * perform all of the services with their durations.
 *
 * @param services the requested services, in request order
 * @param bufferMinutes the largest buffer among the services, applied once after the booking
 * @param serviceDurationsByStaff per qualified staff member, the duration of each service (staff
 * skill override, else branch override, else service default). Staff are not yet filtered by
 * status, bookability or branch assignment.
 */
public record BookingRequirements(List<RequiredService> services, int bufferMinutes,
		Map<UUID, Map<UUID, Integer>> serviceDurationsByStaff) {

	public BookingRequirements {
		services = List.copyOf(services);
		Map<UUID, Map<UUID, Integer>> copy = new HashMap<>();
		serviceDurationsByStaff.forEach((staffId, durations) -> copy.put(staffId, Map.copyOf(durations)));
		serviceDurationsByStaff = Map.copyOf(copy);
	}

	/** Total duration per qualified staff member (the services back to back, without buffer). */
	public Map<UUID, Integer> durationMinutesByStaff() {
		Map<UUID, Integer> totals = new HashMap<>();
		serviceDurationsByStaff.forEach((staffId, durations) -> totals.put(staffId,
				durations.values().stream().mapToInt(Integer::intValue).sum()));
		return Map.copyOf(totals);
	}

	public Optional<Integer> durationMinutesFor(UUID staffId) {
		return Optional.ofNullable(durationMinutesByStaff().get(staffId));
	}

	/** How long the staff member takes for one of the services. */
	public int serviceDurationMinutes(UUID staffId, UUID serviceId) {
		return serviceDurationsByStaff.get(staffId).get(serviceId);
	}

	/**
	 * A requested service at the branch, with branch overrides applied.
	 *
	 * @param durationMinutes the branch duration (staff skills may override it, see
	 * {@link #serviceDurationMinutes})
	 * @param priceFrom the price is a starting price
	 */
	public record RequiredService(UUID serviceId, String name, int durationMinutes, int bufferMinutes,
			BigDecimal price, String currency, boolean priceFrom) {
	}

}
