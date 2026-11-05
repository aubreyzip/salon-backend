package com.salon.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import com.salon.scheduling.application.SchedulingViews.ClosureView;
import com.salon.scheduling.application.SchedulingViews.OpeningHoursView;
import com.salon.scheduling.application.SchedulingViews.SpecialDayView;
import com.salon.scheduling.application.SchedulingViews.WeeklyIntervalView;
import com.salon.scheduling.domain.BranchClosure;
import com.salon.scheduling.domain.BranchClosureRepository;
import com.salon.scheduling.domain.BranchHours;
import com.salon.scheduling.domain.BranchHoursRepository;
import com.salon.scheduling.domain.DateRange;
import com.salon.scheduling.domain.SchedulingErrors;
import com.salon.scheduling.domain.SpecialDay;
import com.salon.scheduling.domain.SpecialDayRepository;
import com.salon.scheduling.domain.TimeInterval;
import com.salon.scheduling.domain.WeeklyInterval;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A branch's opening hours, special days and closures (ADR-0012). Branch setup, so changes need
 * {@code BRANCH_MANAGE} (salon-level, ADR-0010); every member may read them. Inactive branches can
 * be configured too.
 */
@Service
@Transactional
public class BranchCalendarService {

	static final int MAX_LIST_DAYS = 366;

	private static final String ENTITY_BRANCH = "branch";

	private final BranchHoursRepository hours;

	private final SpecialDayRepository specialDays;

	private final BranchClosureRepository closures;

	private final TenantAccess access;

	private final SalonDirectory directory;

	private final ScheduleConflicts conflicts;

	private final AuditLog auditLog;

	private final Clock clock;

	BranchCalendarService(BranchHoursRepository hours, SpecialDayRepository specialDays,
			BranchClosureRepository closures, TenantAccess access, SalonDirectory directory,
			ScheduleConflicts conflicts, AuditLog auditLog, Clock clock) {
		this.hours = hours;
		this.specialDays = specialDays;
		this.closures = closures;
		this.access = access;
		this.directory = directory;
		this.conflicts = conflicts;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public OpeningHoursView openingHours(UUID salonId, UUID branchId, UUID userId) {
		access.requireMember(salonId, userId);
		BranchInfo branch = loadBranch(salonId, branchId);
		List<WeeklyInterval> intervals = hours.findBySalonIdAndBranchId(salonId, branchId)
			.map(BranchHours::getIntervals)
			.orElse(List.of());
		return new OpeningHoursView(branchId, branch.timeZone().getId(), WeeklyIntervalView.of(intervals));
	}

	/** Replaces the weekly opening hours. An empty list means the branch is closed every day. */
	public OpeningHoursView replaceOpeningHours(UUID salonId, UUID branchId, UUID userId,
			List<WeeklyInterval> intervals) {
		Managed managed = checkManage(salonId, branchId, userId);
		BranchInfo branch = managed.branch();
		var newHours = WeeklyInterval.byDay(intervals);
		conflicts.checkBranchChange(managed.caller(), salonId, branchId, branch.timeZone(),
				calendar -> calendar.withOpeningHours(newHours));
		Instant now = clock.instant();
		BranchHours branchHours = hours.findBySalonIdAndBranchId(salonId, branchId)
			.orElseGet(() -> new BranchHours(salonId, branchId, now));
		branchHours.replace(intervals, now);
		hours.save(branchHours);
		audit(userId, salonId, branchId, "scheduling.opening_hours.replaced", null);
		return new OpeningHoursView(branchId, branch.timeZone().getId(),
				WeeklyIntervalView.of(branchHours.getIntervals()));
	}

	@Transactional(readOnly = true)
	public List<SpecialDayView> specialDays(UUID salonId, UUID branchId, UUID userId, LocalDate from, LocalDate to) {
		access.requireMember(salonId, userId);
		BranchInfo branch = loadBranch(salonId, branchId);
		LocalDate start = from != null ? from : LocalDate.now(clock.withZone(branch.timeZone()));
		LocalDate end = to != null ? to : start.plusDays(MAX_LIST_DAYS - 1);
		checkListRange(start, end);
		return specialDays.findBySalonIdAndBranchIdAndOnDateBetweenOrderByOnDateAsc(salonId, branchId, start, end)
			.stream()
			.map(SpecialDayView::of)
			.toList();
	}

	/** Sets the hours of one date, replacing the weekly hours. No intervals: closed that day. */
	public SpecialDayView putSpecialDay(UUID salonId, UUID branchId, LocalDate date, UUID userId,
			List<TimeInterval> intervals, String note) {
		Managed managed = checkManage(salonId, branchId, userId);
		List<TimeInterval> sorted = TimeInterval.sortedNonOverlapping(intervals);
		conflicts.checkBranchChange(managed.caller(), salonId, branchId, managed.branch().timeZone(),
				calendar -> calendar.withSpecialDay(date, sorted));
		SpecialDay day = specialDays.findBySalonIdAndBranchIdAndOnDate(salonId, branchId, date)
			.orElseGet(() -> new SpecialDay(salonId, branchId, date));
		day.change(intervals, note, clock.instant());
		specialDays.save(day);
		audit(userId, salonId, branchId, "scheduling.special_day.set", date.toString());
		return SpecialDayView.of(day);
	}

	/** Removes the special hours of a date, so the weekly hours apply again. Idempotent. */
	public void deleteSpecialDay(UUID salonId, UUID branchId, LocalDate date, UUID userId) {
		Managed managed = checkManage(salonId, branchId, userId);
		specialDays.findBySalonIdAndBranchIdAndOnDate(salonId, branchId, date).ifPresent(day -> {
			// The weekly hours may be shorter than the special day's.
			conflicts.checkBranchChange(managed.caller(), salonId, branchId, managed.branch().timeZone(),
					calendar -> calendar.withoutSpecialDay(date));
			specialDays.delete(day);
			audit(userId, salonId, branchId, "scheduling.special_day.removed", date.toString());
		});
	}

	/**
	 * @param from closures ending on or after this date; defaults to today in the branch zone
	 */
	@Transactional(readOnly = true)
	public List<ClosureView> closures(UUID salonId, UUID branchId, UUID userId, LocalDate from) {
		access.requireMember(salonId, userId);
		BranchInfo branch = loadBranch(salonId, branchId);
		LocalDate start = from != null ? from : LocalDate.now(clock.withZone(branch.timeZone()));
		return closures.findBySalonIdAndBranchIdAndEndDateGreaterThanEqualOrderByStartDateAsc(salonId, branchId, start)
			.stream()
			.map(ClosureView::of)
			.toList();
	}

	public ClosureView createClosure(UUID salonId, UUID branchId, UUID userId, DateRange dates, String reason) {
		Managed managed = checkManage(salonId, branchId, userId);
		if (dates.end() != null && ChronoUnit.DAYS.between(dates.start(), dates.end()) >= MAX_LIST_DAYS) {
			throw SchedulingErrors.invalidDateRange("A closure can be at most 366 days long.");
		}
		conflicts.checkBranchChange(managed.caller(), salonId, branchId, managed.branch().timeZone(),
				calendar -> calendar.withClosure(dates));
		BranchClosure closure = closures.save(new BranchClosure(salonId, branchId, dates, reason, clock.instant()));
		audit(userId, salonId, branchId, "scheduling.closure.created", dates.start() + ".." + dates.end());
		return ClosureView.of(closure);
	}

	public void deleteClosure(UUID salonId, UUID branchId, UUID closureId, UUID userId) {
		checkManage(salonId, branchId, userId);
		BranchClosure closure = closures.findByIdAndSalonIdAndBranchId(closureId, salonId, branchId)
			.orElseThrow(SchedulingErrors::closureNotFound);
		closures.delete(closure);
		audit(userId, salonId, branchId, "scheduling.closure.deleted",
				closure.getStartDate() + ".." + closure.getEndDate());
	}

	private record Managed(SalonMembership caller, BranchInfo branch) {
	}

	/**
	 * Salon lock first (one writer per salon, so "get or create" of the per-branch rows and the
	 * appointment conflict check cannot race), then access, then the suspension check (CLAUDE.md,
	 * salon row lock pattern).
	 */
	private Managed checkManage(UUID salonId, UUID branchId, UUID userId) {
		directory.lockSalon(salonId);
		SalonMembership caller = access.require(salonId, userId, SalonPermission.BRANCH_MANAGE);
		directory.ensureModifiable(salonId);
		return new Managed(caller, loadBranch(salonId, branchId));
	}

	private BranchInfo loadBranch(UUID salonId, UUID branchId) {
		return directory.findBranch(salonId, branchId).orElseThrow(SchedulingErrors::branchNotFound);
	}

	static void checkListRange(LocalDate from, LocalDate to) {
		if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_LIST_DAYS) {
			throw SchedulingErrors.invalidDateRange("The range must be ordered and at most 366 days long.");
		}
	}

	private void audit(UUID userId, UUID salonId, UUID branchId, String action, String details) {
		auditLog.record(new AuditRecord(userId, salonId, action, ENTITY_BRANCH, branchId.toString(), details));
	}

}
