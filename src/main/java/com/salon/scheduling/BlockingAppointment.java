package com.salon.scheduling;

import java.time.Instant;
import java.util.UUID;

/**
 * An appointment that keeps a staff member busy at a branch, as seen by scheduling.
 *
 * @param start when the appointment starts
 * @param blockEnd when the staff member is free again: the end of the services plus the cleanup
 * buffer (the staff member's calendar, not the customer's)
 */
public record BlockingAppointment(UUID id, UUID branchId, UUID staffId, Instant start, Instant blockEnd) {
}
