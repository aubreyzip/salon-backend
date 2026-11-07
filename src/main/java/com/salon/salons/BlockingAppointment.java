package com.salon.salons;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * An appointment that keeps a staff member busy at a branch (ADR-0012 decisions 21 and 26), as
 * seen by the modules whose changes must not silently invalidate it.
 *
 * @param serviceIds the services booked in the appointment
 * @param start when the appointment starts
 * @param blockEnd when the staff member is free again: the end of the services plus the cleanup
 * buffer (the staff member's calendar, not the customer's)
 */
public record BlockingAppointment(UUID id, UUID branchId, UUID staffId, Set<UUID> serviceIds, Instant start,
		Instant blockEnd) {

	public BlockingAppointment {
		serviceIds = Set.copyOf(serviceIds);
	}

}
