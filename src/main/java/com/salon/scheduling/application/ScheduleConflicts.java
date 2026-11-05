package com.salon.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import com.salon.scheduling.BlockingAppointment;
import com.salon.scheduling.BlockingAppointments;
import com.salon.scheduling.application.SchedulingViews.ConflictingAppointmentView;
import com.salon.scheduling.domain.AvailabilityEngine.BranchCalendar;
import com.salon.scheduling.domain.ScheduleCoverage;
import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Validation boundary for schedule changes (ADR-0012, decision 21): a change that takes time away
 * from future blocking appointments is rejected with {@code 409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS}.
 * Appointments come from every {@link BlockingAppointments} in the context (booking, Phase 4); with
 * none, every change passes. The explicit emergency override arrives with booking (Phase 4).
 */
@Component
class ScheduleConflicts {

	static final String CODE = "SCHEDULE_CONFLICTS_WITH_APPOINTMENTS";

	private final List<BlockingAppointments> sources;

	private final BranchCalendars calendars;

	private final Clock clock;

	ScheduleConflicts(List<BlockingAppointments> sources, BranchCalendars calendars, Clock clock) {
		this.sources = sources;
		this.calendars = calendars;
		this.clock = clock;
	}

	/**
	 * Rejects a change of the branch calendar (opening hours, special day, closure) that closes the
	 * branch during an appointment there.
	 * @param change turns the current calendar into the calendar after the change
	 */
	void checkBranchChange(SalonMembership caller, UUID salonId, UUID branchId, ZoneId zone,
			UnaryOperator<BranchCalendar> change) {
		List<BlockingAppointment> appointments = atBranch(salonId, branchId);
		if (appointments.isEmpty()) {
			return;
		}
		LocalDate first = appointments.stream().map(a -> localDate(a, zone)).min(Comparator.naturalOrder()).get();
		LocalDate last = appointments.stream().map(a -> localDate(a, zone)).max(Comparator.naturalOrder()).get();
		BranchCalendar before = calendars.load(salonId, branchId, first, last);
		BranchCalendar after = change.apply(before);
		reject(caller, appointments, appointment -> {
			LocalDate date = localDate(appointment, zone);
			return ScheduleCoverage.losesCoverage(before.openOn(date), after.openOn(date), zone, appointment.start(),
					appointment.blockEnd());
		});
	}

	/** Blocking appointments of the staff member, at one branch or (branchId {@code null}) anywhere. */
	List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId, UUID branchId) {
		Instant now = clock.instant();
		return sources.stream()
			.flatMap(source -> source.ofStaff(salonId, staffId, now).stream())
			.filter(appointment -> branchId == null || appointment.branchId().equals(branchId))
			.toList();
	}

	/**
	 * @throws ApiException {@code 409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS} if any appointment
	 * matches; the problem lists only the appointments the caller may view
	 */
	void reject(SalonMembership caller, List<BlockingAppointment> appointments,
			Predicate<BlockingAppointment> conflicts) {
		List<BlockingAppointment> conflicting = appointments.stream()
			.filter(conflicts)
			.sorted(Comparator.comparing(BlockingAppointment::start))
			.toList();
		if (conflicting.isEmpty()) {
			return;
		}
		List<ConflictingAppointmentView> visible = conflicting.stream()
			.filter(appointment -> visibleTo(caller, appointment))
			.map(ConflictingAppointmentView::of)
			.toList();
		throw new ApiException(HttpStatus.CONFLICT, CODE,
				"The change conflicts with " + conflicting.size()
						+ " upcoming appointment(s). Move or cancel them first.",
				Map.of("conflictCount", conflicting.size(), "appointments", visible));
	}

	/** Same rules as appointment reads (ADR-0010): any in a covered branch, or one's own. */
	static boolean visibleTo(SalonMembership caller, BlockingAppointment appointment) {
		return (caller.has(SalonPermission.APPOINTMENT_VIEW_ANY) && caller.coversBranch(appointment.branchId()))
				|| (caller.has(SalonPermission.APPOINTMENT_VIEW_OWN)
						&& caller.isOwnStaffResource(appointment.staffId()));
	}

	private List<BlockingAppointment> atBranch(UUID salonId, UUID branchId) {
		Instant now = clock.instant();
		return sources.stream().flatMap(source -> source.atBranch(salonId, branchId, now).stream()).toList();
	}

	private static LocalDate localDate(BlockingAppointment appointment, ZoneId zone) {
		return LocalDate.ofInstant(appointment.start(), zone);
	}

}
