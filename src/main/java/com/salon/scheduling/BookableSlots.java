package com.salon.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.catalog.BookingRequirements;
import com.salon.salons.BranchInfo;
import com.salon.scheduling.application.AvailabilityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks one exact start for booking and rescheduling (ADR-0013): which staff members are free for
 * the whole booking, under the customer or the salon booking rules. Booking calls it inside its
 * transaction, under the salon row lock in share mode; the exclusion constraint stays the final
 * arbiter for concurrent bookings.
 */
@Service
@Transactional(readOnly = true)
public class BookableSlots {

	/** Salon bookings may start this long in the past (walk-ins, decision 37). */
	public static final Duration SALON_PAST_TOLERANCE = Duration.ofMinutes(15);

	/** Salon bookings may be made this many days ahead, whatever the branch's customer limit (decision 37). */
	public static final int SALON_MAX_ADVANCE_DAYS = 365;

	/** Whose rules apply. */
	public enum Booker {

		/** On the branch's slot grid, with its minimum notice and maximum advance. */
		CUSTOMER,

		/** Any whole minute, up to 15 minutes in the past and 365 days ahead. */
		SALON

	}

	/**
	 * A staff member free for the whole booking.
	 *
	 * @param durationMinutes how long this staff member takes for the services (without buffer)
	 */
	public record FreeStaff(UUID staffId, int durationMinutes) {
	}

	private final AvailabilityService availability;

	BookableSlots(AvailabilityService availability) {
		this.availability = availability;
	}

	/**
	 * Staff members free at {@code start} for the services: active, bookable, assigned to the
	 * branch, skilled in every service, within opening and working hours, without time off or a
	 * blocking appointment in {@code [start, start + duration + buffer)}. Ordered by staff id.
	 * @param staffId only this staff member, or {@code null} for anyone qualified
	 * @param ignoredAppointmentId an appointment to disregard (the one being rescheduled), or {@code null}
	 * @throws com.salon.shared.ApiException {@code 400 INVALID_START} if the start breaks the rules of
	 * the booker (grid, notice, advance)
	 */
	public List<FreeStaff> freeStaff(BranchInfo branch, BookingRequirements requirements, Instant start,
			UUID staffId, UUID ignoredAppointmentId, Booker booker) {
		return availability.freeStaffAt(branch, requirements, start, staffId, ignoredAppointmentId, booker);
	}

}
