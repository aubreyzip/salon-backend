package com.salon.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inputs of the booking use cases. They are records, so their string form is deterministic and
 * serves as the request fingerprint bound to an {@code Idempotency-Key}.
 */
public final class BookingCommands {

	private BookingCommands() {
	}

	/**
	 * A customer's online booking (decision 27).
	 *
	 * @param staffId the requested staff member, or {@code null} for anyone available (decision 28)
	 * @param customerNote visible to the salon, or {@code null}
	 */
	public record BookOnline(UUID branchId, List<UUID> serviceIds, UUID staffId, Instant start,
			String customerNote) {

		public BookOnline {
			serviceIds = List.copyOf(serviceIds);
		}

	}

	/**
	 * A booking by salon staff (decision 37).
	 *
	 * @param staffId the staff member, or {@code null} for anyone available
	 * @param internalNote never shown to the customer, or {@code null}
	 */
	public record BookAtSalon(UUID branchId, UUID clientId, List<UUID> serviceIds, UUID staffId, Instant start,
			String internalNote) {

		public BookAtSalon {
			serviceIds = List.copyOf(serviceIds);
		}

	}

	/**
	 * @param staffId the new staff member, or {@code null} to keep the current one
	 */
	public record Reschedule(Instant start, UUID staffId) {
	}

}
