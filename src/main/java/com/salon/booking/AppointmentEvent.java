package com.salon.booking;

import java.util.UUID;

/**
 * Domain events of the booking module (ADR-0013 decision 39), published inside the booking
 * transaction through the Modulith event publication registry. They carry ids only (ADR-0011);
 * listeners load what they need. Phase 4 has no listeners; notifications (Phase 5) subscribe with
 * {@code @ApplicationModuleListener} and must be idempotent.
 */
public sealed interface AppointmentEvent {

	UUID appointmentId();

	UUID salonId();

	UUID branchId();

	/** The account-linked customer, or {@code null} for walk-in clients. */
	UUID customerUserId();

	/** Booked by the customer or the salon, {@code PENDING} or {@code CONFIRMED}. */
	record Booked(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId) implements AppointmentEvent {
	}

	record Confirmed(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	record Rejected(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	/** Not confirmed in time (ADR-0009). */
	record Expired(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId) implements AppointmentEvent {
	}

	/**
	 * @param byCustomer cancelled by the customer, otherwise by the salon
	 */
	record Cancelled(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId, boolean byCustomer)
			implements AppointmentEvent {
	}

	record Rescheduled(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	record Completed(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

	record NoShow(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId) implements AppointmentEvent {
	}

	/** Flagged by an emergency override of a conflicting change (ADR-0012 decisions 21, 25, 26). */
	record NeedsAttention(UUID appointmentId, UUID salonId, UUID branchId, UUID customerUserId)
			implements AppointmentEvent {
	}

}
