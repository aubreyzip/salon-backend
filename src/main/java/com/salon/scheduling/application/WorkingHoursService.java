package com.salon.scheduling.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import com.salon.salons.StaffInfo;
import com.salon.salons.TenantAccess;
import com.salon.scheduling.application.SchedulingViews.PeriodView;
import com.salon.scheduling.application.SchedulingViews.WorkingHoursView;
import com.salon.scheduling.domain.DateRange;
import com.salon.scheduling.domain.SchedulingErrors;
import com.salon.scheduling.domain.WeeklyInterval;
import com.salon.scheduling.domain.WorkingPeriod;
import com.salon.scheduling.domain.WorkingPeriodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff working hours per branch, as date-bounded weekly periods (ADR-0012). Changes need
 * {@code SCHEDULE_MANAGE_ANY}, or {@code SCHEDULE_MANAGE_OWN} for one's own hours, in a branch the
 * caller works in (ADR-0010).
 * <p>
 * Invariants, checked under the salon row lock: periods of one staff member at one branch do not
 * overlap in dates, and a staff member's hours at different branches do not overlap in time.
 */
@Service
@Transactional
public class WorkingHoursService {

	private final WorkingPeriodRepository periods;

	private final TenantAccess access;

	private final SalonDirectory directory;

	private final AuditLog auditLog;

	private final Clock clock;

	WorkingHoursService(WorkingPeriodRepository periods, TenantAccess access, SalonDirectory directory,
			AuditLog auditLog, Clock clock) {
		this.periods = periods;
		this.access = access;
		this.directory = directory;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * @param dates the end is inclusive; {@code null} = open-ended
	 */
	public record PeriodInput(DateRange dates, List<WeeklyInterval> intervals) {
	}

	/**
	 * A staff member's working hours, grouped by branch. Branch-scoped members see only their
	 * branches, except for their own hours.
	 */
	@Transactional(readOnly = true)
	public List<WorkingHoursView> list(UUID salonId, UUID staffId, UUID userId) {
		SalonMembership membership = access.requireMember(salonId, userId);
		loadStaff(salonId, staffId);
		boolean own = membership.isOwnStaffResource(staffId);
		Optional<Set<UUID>> restriction = own ? Optional.empty() : membership.branchRestriction();
		Map<UUID, List<PeriodView>> byBranch = new TreeMap<>();
		periods.findBySalonIdAndStaffIdOrderByEffectiveFromAsc(salonId, staffId)
			.stream()
			.filter(period -> restriction.map(ids -> ids.contains(period.getBranchId())).orElse(true))
			.forEach(period -> byBranch.computeIfAbsent(period.getBranchId(), id -> new ArrayList<>())
				.add(PeriodView.of(period)));
		return byBranch.entrySet().stream().map(e -> new WorkingHoursView(e.getKey(), e.getValue())).toList();
	}

	/**
	 * Replaces the staff member's working hours at one branch. An empty list removes them.
	 */
	public WorkingHoursView replace(UUID salonId, UUID staffId, UUID branchId, UUID userId,
			List<PeriodInput> inputs) {
		directory.lockSalon(salonId);
		access.requireForStaffResource(salonId, branchId, staffId, userId, SalonPermission.SCHEDULE_MANAGE_ANY,
				SalonPermission.SCHEDULE_MANAGE_OWN);
		directory.ensureModifiable(salonId);
		directory.findBranch(salonId, branchId).orElseThrow(SchedulingErrors::branchNotFound);
		StaffInfo staff = loadStaff(salonId, staffId);
		if (!inputs.isEmpty()) {
			if (!staff.active()) {
				throw SchedulingErrors.staffInactive();
			}
			if (!staff.branchIds().contains(branchId)) {
				throw SchedulingErrors.staffNotAssignedToBranch();
			}
		}

		Instant now = clock.instant();
		List<WorkingPeriod> replacement = inputs.stream()
			.sorted(Comparator.comparing(input -> input.dates().start()))
			.map(input -> new WorkingPeriod(salonId, staffId, branchId, input.dates(), input.intervals(), now))
			.toList();
		checkNoDateOverlap(replacement);

		List<WorkingPeriod> existing = periods.findBySalonIdAndStaffIdOrderByEffectiveFromAsc(salonId, staffId);
		List<WorkingPeriod> otherBranches = existing.stream()
			.filter(period -> !period.getBranchId().equals(branchId))
			.toList();
		for (WorkingPeriod period : replacement) {
			for (WorkingPeriod other : otherBranches) {
				period.pattern().conflictWith(other.pattern()).ifPresent(day -> {
					throw conflict(day, other);
				});
			}
		}

		periods.deleteAll(existing.stream().filter(period -> period.getBranchId().equals(branchId)).toList());
		periods.flush();
		periods.saveAll(replacement);
		auditLog.record(new AuditRecord(userId, salonId, "scheduling.working_hours.replaced", "staff_member",
				staffId.toString(), "branch " + branchId + ", " + replacement.size() + " periods"));
		return new WorkingHoursView(branchId, replacement.stream().map(PeriodView::of).toList());
	}

	private static void checkNoDateOverlap(List<WorkingPeriod> sortedByStart) {
		for (int i = 1; i < sortedByStart.size(); i++) {
			if (sortedByStart.get(i - 1).dates().intersection(sortedByStart.get(i).dates()).isPresent()) {
				throw SchedulingErrors.overlappingPeriods();
			}
		}
	}

	private static RuntimeException conflict(DayOfWeek day, WorkingPeriod other) {
		return SchedulingErrors.workingHoursConflict("The hours overlap with this staff member's hours at branch "
				+ other.getBranchId() + " on " + day.name().toLowerCase(Locale.ROOT) + "s.");
	}

	private StaffInfo loadStaff(UUID salonId, UUID staffId) {
		return directory.findStaff(salonId, staffId).orElseThrow(SchedulingErrors::staffNotFound);
	}

}
