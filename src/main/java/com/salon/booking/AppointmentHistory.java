package com.salon.booking;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the immutable history entry an {@link AppointmentEvent} names (ADR-0014 decision
 * 56). Consumers describe a change from this snapshot, not from the current appointment.
 */
public interface AppointmentHistory {

	Optional<Change> find(UUID historyEntryId);

	/**
	 * One recorded change, as it was at that moment.
	 * @param action e.g. {@code BOOKED}, {@code RESCHEDULED}, {@code CANCELLED}
	 * @param fromStatus the status before the change, {@code null} for the booking
	 * @param toStatus the status after the change
	 * @param actorUserId who made the change, {@code null} for the system
	 * @param reason the reason given with the change, if any (shown to the customer for salon
	 * cancellations and rejections, ADR-0013 decision 34)
	 * @param previousStartAt for reschedules: the start before the change, otherwise {@code null}
	 * @param endAt {@code null} for entries written before Phase 5
	 */
	record Change(UUID id, UUID appointmentId, UUID salonId, String action, String fromStatus, String toStatus,
			UUID actorUserId, String reason, Instant startAt, Instant endAt, UUID staffId, Instant previousStartAt,
			Instant previousEndAt, UUID previousStaffId, Instant occurredAt) {
	}

}
