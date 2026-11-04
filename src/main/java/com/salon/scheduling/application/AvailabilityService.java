package com.salon.scheduling.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salon.catalog.BookingRequirements;
import com.salon.catalog.ServiceCatalog;
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import com.salon.salons.StaffInfo;
import com.salon.scheduling.application.SchedulingViews.AvailabilityView;
import com.salon.scheduling.application.SchedulingViews.DayView;
import com.salon.scheduling.application.SchedulingViews.ServiceSummary;
import com.salon.scheduling.application.SchedulingViews.SlotView;
import com.salon.scheduling.application.SchedulingViews.StaffSummary;
import com.salon.scheduling.domain.AvailabilityEngine;
import com.salon.scheduling.domain.AvailabilityEngine.BranchCalendar;
import com.salon.scheduling.domain.AvailabilityEngine.StaffCalendar;
import com.salon.scheduling.domain.BranchClosure;
import com.salon.scheduling.domain.BranchClosureRepository;
import com.salon.scheduling.domain.BranchHours;
import com.salon.scheduling.domain.BranchHoursRepository;
import com.salon.scheduling.domain.InstantRange;
import com.salon.scheduling.domain.SchedulingErrors;
import com.salon.scheduling.domain.SpecialDay;
import com.salon.scheduling.domain.SpecialDayRepository;
import com.salon.scheduling.domain.TimeInterval;
import com.salon.scheduling.domain.TimeOff;
import com.salon.scheduling.domain.TimeOffRepository;
import com.salon.scheduling.domain.WorkingPeriod;
import com.salon.scheduling.domain.WorkingPeriodRepository;
import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads what the {@link AvailabilityEngine} needs and runs it (report §7, ADR-0012).
 * <p>
 * Customer view: only public branches (active branch, active salon), services that are offered at
 * the branch and bookable online, and staff who are active, bookable, assigned to the branch and
 * skilled in every requested service. The branch's minimum notice applies. Phase 4 adds blocking
 * appointments as absences and a salon-side view without these customer restrictions.
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

	/** Longest range per query (one month view). */
	public static final int MAX_DAYS = 31;

	public static final int MAX_SERVICES = 5;

	private final SalonDirectory directory;

	private final ServiceCatalog catalog;

	private final BranchHoursRepository hours;

	private final SpecialDayRepository specialDays;

	private final BranchClosureRepository closures;

	private final WorkingPeriodRepository periods;

	private final TimeOffRepository timeOffs;

	private final Clock clock;

	AvailabilityService(SalonDirectory directory, ServiceCatalog catalog, BranchHoursRepository hours,
			SpecialDayRepository specialDays, BranchClosureRepository closures, WorkingPeriodRepository periods,
			TimeOffRepository timeOffs, Clock clock) {
		this.directory = directory;
		this.catalog = catalog;
		this.hours = hours;
		this.specialDays = specialDays;
		this.closures = closures;
		this.periods = periods;
		this.timeOffs = timeOffs;
		this.clock = clock;
	}

	/**
	 * @param staffId only this staff member, or {@code null} for anyone qualified
	 */
	public AvailabilityView publicAvailability(UUID branchId, List<UUID> serviceIds, LocalDate from, LocalDate to,
			UUID staffId) {
		BranchInfo branch = directory.findPublicBranch(branchId).orElseThrow(SchedulingErrors::branchNotFound);
		checkQuery(serviceIds, from, to);
		UUID salonId = branch.salonId();
		BookingRequirements requirements = catalog.requirements(salonId, branchId, serviceIds, true);

		List<StaffInfo> staff = directory.listStaff(salonId)
			.stream()
			.filter(member -> member.isBookableAt(branchId))
			.filter(member -> requirements.durationMinutesByStaff().containsKey(member.id()))
			.filter(member -> staffId == null || member.id().equals(staffId))
			.toList();
		List<UUID> staffIds = staff.stream().map(StaffInfo::id).toList();

		ZoneId zone = branch.timeZone();
		Instant windowStart = from.atStartOfDay(zone).toInstant();
		Instant windowEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
		Map<UUID, List<WorkingPeriod>> periodsByStaff = staffIds.isEmpty() ? Map.of()
				: periods.findBySalonIdAndBranchIdAndStaffIdIn(salonId, branchId, staffIds)
					.stream()
					.collect(Collectors.groupingBy(WorkingPeriod::getStaffId));
		Map<UUID, List<InstantRange>> absencesByStaff = staffIds.isEmpty() ? Map.of()
				: timeOffs.findBySalonIdAndStaffIdInAndEndAtAfterAndStartAtBefore(salonId, staffIds, windowStart,
						windowEnd)
					.stream()
					.collect(Collectors.groupingBy(TimeOff::getStaffId,
							Collectors.mapping(TimeOff::range, Collectors.toList())));

		List<StaffCalendar> calendars = staff.stream()
			.map(member -> new StaffCalendar(member.id(), requirements.durationMinutesByStaff().get(member.id()),
					periodsByStaff.getOrDefault(member.id(), List.of()).stream().map(WorkingPeriod::pattern).toList(),
					absencesByStaff.getOrDefault(member.id(), List.of())))
			.toList();

		var days = AvailabilityEngine.compute(new AvailabilityEngine.Query(zone, clock.instant(), from, to,
				branch.slotIntervalMinutes(), Duration.ofMinutes(branch.minNoticeMinutes()), branch.maxAdvanceDays(),
				requirements.bufferMinutes(), branchCalendar(salonId, branchId, from, to), calendars));

		return new AvailabilityView(branchId, zone.getId(),
				requirements.services()
					.stream()
					.map(s -> new ServiceSummary(s.serviceId(), s.name(), s.durationMinutes(), s.price(), s.currency(),
							s.priceFrom()))
					.toList(),
				staff.stream()
					.map(member -> new StaffSummary(member.id(), member.displayName(),
							requirements.durationMinutesByStaff().get(member.id())))
					.toList(),
				days.stream()
					.map(day -> new DayView(day.date(),
							day.slots()
								.stream()
								.map(slot -> new SlotView(slot.start(), slot.localTime(), slot.staffIds()))
								.toList()))
					.toList());
	}

	private BranchCalendar branchCalendar(UUID salonId, UUID branchId, LocalDate from, LocalDate to) {
		var openingHours = hours.findBySalonIdAndBranchId(salonId, branchId)
			.map(BranchHours::byDay)
			.orElse(Map.of());
		Map<LocalDate, List<TimeInterval>> special = specialDays
			.findBySalonIdAndBranchIdAndOnDateBetweenOrderByOnDateAsc(salonId, branchId, from, to)
			.stream()
			.collect(Collectors.toMap(SpecialDay::getOnDate, SpecialDay::getIntervals));
		var closed = closures
			.findBySalonIdAndBranchIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(salonId, branchId, to, from)
			.stream()
			.map(BranchClosure::dates)
			.toList();
		return new BranchCalendar(openingHours, special, closed);
	}

	private static void checkQuery(List<UUID> serviceIds, LocalDate from, LocalDate to) {
		if (serviceIds == null || serviceIds.isEmpty() || serviceIds.size() > MAX_SERVICES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SERVICES",
					"Request between 1 and " + MAX_SERVICES + " services.");
		}
		if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
			throw SchedulingErrors.invalidDateRange("The range must be ordered and at most " + MAX_DAYS + " days long.");
		}
	}

}
