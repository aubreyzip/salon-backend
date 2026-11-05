package com.salon.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Extension point (ADR-0012, decision 21): the appointments that schedule changes must not
 * silently invalidate. Implemented by the booking module (Phase 4), which depends on scheduling;
 * this interface inverts that dependency so scheduling can ask without a module cycle. Scheduling
 * uses every implementation in the context; with none (Phase 3), nothing blocks a change.
 * <p>
 * Implementations return only appointments in a <b>blocking</b> status ({@code PENDING},
 * {@code CONFIRMED}) whose {@code blockEnd} is after {@code endingAfter}. Cancelled, rejected,
 * completed, no-show and expired appointments never block. Calls run inside the schedule change's
 * transaction, after its access checks and under the salon row lock where the change takes it.
 */
public interface BlockingAppointments {

	/** Blocking appointments at the branch (any staff member). */
	List<BlockingAppointment> atBranch(UUID salonId, UUID branchId, Instant endingAfter);

	/** Blocking appointments of the staff member (any branch). */
	List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId, Instant endingAfter);

}
