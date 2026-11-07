package com.salon.scheduling.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import com.salon.salons.AppointmentConflicts;
import com.salon.salons.BlockingAppointment;
import com.salon.salons.ConflictOverride;
import com.salon.salons.SalonMembership;
import com.salon.scheduling.domain.AvailabilityEngine.BranchCalendar;
import com.salon.scheduling.domain.ScheduleCoverage;
import org.springframework.stereotype.Component;

/**
 * Schedule-specific part of the validation boundary (ADR-0012, decision 21): which appointments a
 * schedule change takes time away from. Rejecting (409) and the emergency override are shared with
 * staff and catalog changes in {@link AppointmentConflicts}.
 */
@Component
class ScheduleConflicts {

	private final AppointmentConflicts conflicts;

	private final BranchCalendars calendars;

	ScheduleConflicts(AppointmentConflicts conflicts, BranchCalendars calendars) {
		this.conflicts = conflicts;
		this.calendars = calendars;
	}

	/**
	 * Rejects a change of the branch calendar (opening hours, special day, closure) that closes the
	 * branch during an appointment there, unless overridden.
	 * @param change turns the current calendar into the calendar after the change
	 */
	void checkBranchChange(SalonMembership caller, UUID salonId, UUID branchId, ZoneId zone,
			UnaryOperator<BranchCalendar> change, ConflictOverride override, String description) {
		List<BlockingAppointment> appointments = conflicts.atBranch(salonId, branchId);
		if (appointments.isEmpty()) {
			return;
		}
		LocalDate first = appointments.stream().map(a -> localDate(a, zone)).min(Comparator.naturalOrder()).get();
		LocalDate last = appointments.stream().map(a -> localDate(a, zone)).max(Comparator.naturalOrder()).get();
		BranchCalendar before = calendars.load(salonId, branchId, first, last);
		BranchCalendar after = change.apply(before);
		conflicts.check(caller, appointments, appointment -> {
			LocalDate date = localDate(appointment, zone);
			return ScheduleCoverage.losesCoverage(before.openOn(date), after.openOn(date), zone, appointment.start(),
					appointment.blockEnd());
		}, override, description);
	}

	/** Future blocking appointments of the staff member, at one branch or (branchId {@code null}) anywhere. */
	List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId, UUID branchId) {
		return conflicts.ofStaff(salonId, staffId)
			.stream()
			.filter(appointment -> branchId == null || appointment.branchId().equals(branchId))
			.toList();
	}

	void check(SalonMembership caller, List<BlockingAppointment> appointments,
			Predicate<BlockingAppointment> conflicting, ConflictOverride override, String description) {
		conflicts.check(caller, appointments, conflicting, override, description);
	}

	private static LocalDate localDate(BlockingAppointment appointment, ZoneId zone) {
		return LocalDate.ofInstant(appointment.start(), zone);
	}

}
