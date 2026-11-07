package com.salon.salons;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Extension point (ADR-0012 decisions 21 and 26, ADR-0013): the appointments that schedule, staff
 * and catalog changes must not silently invalidate. Implemented by the booking module, which
 * depends on every module that asks; this interface inverts that dependency so {@code salons},
 * {@code catalog} and {@code scheduling} can ask without a module cycle. Callers use every
 * implementation in the context; with none, nothing blocks a change.
 * <p>
 * Implementations return only appointments in a <b>blocking</b> status ({@code PENDING},
 * {@code CONFIRMED}). Cancelled, rejected, completed, no-show and expired appointments never block.
 * Calls run inside the caller's transaction, after its access checks and under the salon row lock.
 */
public interface BlockingAppointments {

	/** Blocking appointments at the branch (any staff member) with {@code blockEnd > endingAfter}. */
	List<BlockingAppointment> atBranch(UUID salonId, UUID branchId, Instant endingAfter);

	/** Blocking appointments of the staff member (any branch) with {@code blockEnd > endingAfter}. */
	List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId, Instant endingAfter);

	/** Blocking appointments that include the service, with {@code blockEnd > endingAfter}. */
	List<BlockingAppointment> withService(UUID salonId, UUID serviceId, Instant endingAfter);

	/** Blocking appointments of these staff members overlapping {@code [from, to)} (availability). */
	List<BlockingAppointment> ofStaffBetween(UUID salonId, Collection<UUID> staffIds, Instant from, Instant to);

	/**
	 * Emergency override (decision 25): flags the appointments as needing attention, with the
	 * reason, keeping their status. Ids the implementation does not know are ignored.
	 */
	void flagForAttention(UUID salonId, Collection<UUID> appointmentIds, String reason, UUID actorUserId);

}
