package com.salon.booking;

import java.util.UUID;

/**
 * Domain events of the booking module (ADR-0013 decision 39), published inside the booking
 * transaction through the Modulith event publication registry. They carry ids only (ADR-0011).
 * <p>
 * {@link #historyEntryId()} names the immutable history entry written in the same transaction
 * (ADR-0014 decision 56). Consumers describe the change from that entry, never from the current
 * appointment row, which a later change may already have overwritten. Load it through
 * {@link AppointmentHistory}.
 * <p>
 * Stored publications must still deserialize after an upgrade: from Phase 5 on, changes to these
 * records are additive only. Listeners must be idempotent.
 */
public sealed interface AppointmentEvent {

	UUID historyEntryId();

	UUID appointmentId();

	UUID salonId();

	UUID branchId();

	/** The account-linked customer, or {@code null} for walk-in clients. */
	UUID customerUserId();

	/** Booked by the customer or the salon, {@code PENDING} or {@code CONFIRMED}. */
	record Booked(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	record Confirmed(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	record Rejected(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	/** Not confirmed in time (ADR-0009). */
	record Expired(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	/**
	 * @param byCustomer cancelled by the customer, otherwise by the salon
	 */
	record Cancelled(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId,
			boolean byCustomer) implements AppointmentEvent {
	}

	record Rescheduled(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	record Completed(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	record NoShow(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	/** Flagged by an emergency override of a conflicting change (ADR-0012 decisions 21, 25, 26). */
	record NeedsAttention(UUID historyEntryId, UUID appointmentId, UUID salonId, UUID branchId,
			UUID customerUserId) implements AppointmentEvent {
	}

}
