package com.salon.scheduling.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import com.salon.scheduling.application.SchedulingViews.TimeOffView;
import com.salon.scheduling.domain.InstantRange;
import com.salon.scheduling.domain.SchedulingErrors;
import com.salon.scheduling.domain.TimeOff;
import com.salon.scheduling.domain.TimeOffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff time off (ADR-0012): full or partial days, effective immediately, audited. Not tied to a
 * branch. Reading and changing need {@code SCHEDULE_MANAGE_ANY}, or {@code SCHEDULE_MANAGE_OWN} for
 * one's own time off; the reason may be personal, so other members do not see it.
 */
@Service
@Transactional
public class TimeOffService {

	private static final Duration MAX_LIST_RANGE = Duration.ofDays(400);

	private final TimeOffRepository timeOffs;

	private final TenantAccess access;

	private final SalonDirectory directory;

	private final ScheduleConflicts conflicts;

	private final AuditLog auditLog;

	private final Clock clock;

	TimeOffService(TimeOffRepository timeOffs, TenantAccess access, SalonDirectory directory,
			ScheduleConflicts conflicts, AuditLog auditLog, Clock clock) {
		this.timeOffs = timeOffs;
		this.access = access;
		this.directory = directory;
		this.conflicts = conflicts;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * Time off overlapping {@code [from, to)}; defaults: from now, for one year.
	 */
	@Transactional(readOnly = true)
	public List<TimeOffView> list(UUID salonId, UUID staffId, UUID userId, Instant from, Instant to) {
		checkAccess(salonId, staffId, userId);
		Instant start = from != null ? from : clock.instant();
		Instant end = to != null ? to : start.plus(Duration.ofDays(366));
		if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_LIST_RANGE) > 0) {
			throw SchedulingErrors.invalidTimeRange("The range must be ordered and at most 400 days long.");
		}
		return timeOffs.findBySalonIdAndStaffIdAndEndAtAfterAndStartAtBeforeOrderByStartAtAsc(salonId, staffId, start,
				end)
			.stream()
			.map(TimeOffView::of)
			.toList();
	}

	/**
	 * Takes the salon row lock, so the appointment check (decision 21) cannot race a booking.
	 */
	public TimeOffView create(UUID salonId, UUID staffId, UUID userId, Instant startAt, Instant endAt,
			String reason) {
		directory.lockSalon(salonId);
		SalonMembership caller = checkAccess(salonId, staffId, userId);
		directory.ensureModifiable(salonId);
		TimeOff timeOff = new TimeOff(salonId, staffId, startAt, endAt, reason, userId, clock.instant());
		conflicts.reject(caller, conflicts.ofStaff(salonId, staffId, null),
				appointment -> timeOff.range().overlaps(new InstantRange(appointment.start(), appointment.blockEnd())));
		timeOffs.save(timeOff);
		audit(userId, salonId, "scheduling.time_off.created", timeOff);
		return TimeOffView.of(timeOff);
	}

	public void delete(UUID salonId, UUID staffId, UUID timeOffId, UUID userId) {
		checkAccess(salonId, staffId, userId);
		directory.ensureModifiable(salonId);
		TimeOff timeOff = timeOffs.findByIdAndSalonIdAndStaffId(timeOffId, salonId, staffId)
			.orElseThrow(SchedulingErrors::timeOffNotFound);
		timeOffs.delete(timeOff);
		audit(userId, salonId, "scheduling.time_off.deleted", timeOff);
	}

	private SalonMembership checkAccess(UUID salonId, UUID staffId, UUID userId) {
		return access.requireForStaffMember(salonId, staffId, userId, SalonPermission.SCHEDULE_MANAGE_ANY,
				SalonPermission.SCHEDULE_MANAGE_OWN);
	}

	/** The reason is not audited: it may contain personal data. */
	private void audit(UUID userId, UUID salonId, String action, TimeOff timeOff) {
		auditLog.record(new AuditRecord(userId, salonId, action, "staff_member", timeOff.getStaffId().toString(),
				timeOff.getStartAt() + ".." + timeOff.getEndAt()));
	}

}
