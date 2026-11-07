package com.salon.scheduling.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.salon.catalog.BookingRequirements;
import com.salon.catalog.ServiceCatalog;
import com.salon.salons.BlockingAppointment;
import com.salon.salons.BlockingAppointments;
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonPermission;
import com.salon.salons.StaffInfo;
import com.salon.salons.TenantAccess;
import com.salon.scheduling.BookableSlots;
import com.salon.scheduling.BookableSlots.Booker;
import com.salon.scheduling.BookableSlots.FreeStaff;
import com.salon.scheduling.application.SchedulingViews.AvailabilityView;
import com.salon.scheduling.application.SchedulingViews.DayView;
import com.salon.scheduling.application.SchedulingViews.ServiceSummary;
import com.salon.scheduling.application.SchedulingViews.SlotView;
import com.salon.scheduling.application.SchedulingViews.StaffSummary;
import com.salon.scheduling.domain.AvailabilityEngine;
import com.salon.scheduling.domain.AvailabilityEngine.BranchCalendar;
import com.salon.scheduling.domain.AvailabilityEngine.StaffCalendar;
import com.salon.scheduling.domain.InstantRange;
import com.salon.scheduling.domain.SchedulingErrors;
import com.salon.scheduling.domain.TimeOff;
import com.salon.scheduling.domain.TimeOffRepository;
import com.salon.scheduling.domain.WorkingPeriod;
import com.salon.scheduling.domain.WorkingPeriodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads what the {@link AvailabilityEngine} needs and runs it (report §7, ADR-0012, ADR-0013).
 * Absences are time off and blocking appointments (from every {@link BlockingAppointments}).
 * <p>
 * Candidates are always staff who are active, bookable, assigned to the branch and skilled in every
 * requested service. Customer view: only public branches (active branch, active salon) and services
 * bookable online; the branch's minimum notice and advance limit apply. Salon view (decision 20/37):
 * every service, any branch, up to 15 minutes in the past and 365 days ahead.
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

	/** Longest range per query (one month view). */
	public static final int MAX_DAYS = 31;

	public static final int MAX_SERVICES = 5;

	private final SalonDirectory directory;

	private final TenantAccess access;

	private final ServiceCatalog catalog;

	private final BranchCalendars calendars;

	private final WorkingPeriodRepository periods;

	private final TimeOffRepository timeOffs;

	private final List<BlockingAppointments> appointments;

	private final Clock clock;

	AvailabilityService(SalonDirectory directory, TenantAccess access, ServiceCatalog catalog,
			BranchCalendars calendars, WorkingPeriodRepository periods, TimeOffRepository timeOffs,
			List<BlockingAppointments> appointments, Clock clock) {
		this.directory = directory;
		this.access = access;
		this.catalog = catalog;
		this.calendars = calendars;
		this.periods = periods;
		this.timeOffs = timeOffs;
		this.appointments = appointments;
		this.clock = clock;
	}

	/**
	 * @param staffId only this staff member, or {@code null} for anyone qualified
	 */
	public AvailabilityView publicAvailability(UUID branchId, List<UUID> serviceIds, LocalDate from, LocalDate to,
			UUID staffId) {
		BranchInfo branch = directory.findPublicBranch(branchId).orElseThrow(SchedulingErrors::branchNotFound);
		checkQuery(serviceIds, from, to);
		BookingRequirements requirements = catalog.requirements(branch.salonId(), branchId, serviceIds, true);
		return view(branch, requirements, from, to, staffId, Duration.ofMinutes(branch.minNoticeMinutes()),
				branch.maxAdvanceDays());
	}

	/**
	 * Salon-side availability (decision 20): every service offered at the branch, active or inactive
	 * branch. Needs {@code APPOINTMENT_MANAGE_ANY} in the branch, or {@code APPOINTMENT_MANAGE_OWN}
	 * with the caller's own {@code staffId}.
	 */
	public AvailabilityView salonAvailability(UUID salonId, UUID branchId, UUID userId, List<UUID> serviceIds,
			LocalDate from, LocalDate to, UUID staffId) {
		if (staffId == null) {
			access.requireInBranch(salonId, branchId, userId, SalonPermission.APPOINTMENT_MANAGE_ANY);
		}
		else {
			access.requireForStaffResource(salonId, branchId, staffId, userId, SalonPermission.APPOINTMENT_MANAGE_ANY,
					SalonPermission.APPOINTMENT_MANAGE_OWN);
		}
		BranchInfo branch = directory.findBranch(salonId, branchId).orElseThrow(SchedulingErrors::branchNotFound);
		checkQuery(serviceIds, from, to);
		BookingRequirements requirements = catalog.requirements(salonId, branchId, serviceIds, false);
		return view(branch, requirements, from, to, staffId, BookableSlots.SALON_PAST_TOLERANCE.negated(),
				BookableSlots.SALON_MAX_ADVANCE_DAYS);
	}

	/** See {@link BookableSlots#freeStaff}. */
	public List<FreeStaff> freeStaffAt(BranchInfo branch, BookingRequirements requirements, Instant start,
			UUID staffId, UUID ignoredAppointmentId, Booker booker) {
		checkStart(branch, start, booker);
		ZoneId zone = branch.timeZone();
		LocalDate date = LocalDate.ofInstant(start, zone);
		List<StaffInfo> staff = candidates(branch, requirements, staffId);
		if (staff.isEmpty()) {
			return List.of();
		}
		BranchCalendar calendar = calendars.load(branch.salonId(), branch.id(), date, date);
		return staffCalendars(branch, requirements, staff, date.atStartOfDay(zone).toInstant(),
				date.plusDays(1).atStartOfDay(zone).toInstant(), ignoredAppointmentId)
			.stream()
			.filter(staffCalendar -> AvailabilityEngine.isFree(calendar, staffCalendar, zone, start,
					requirements.bufferMinutes()))
			.map(staffCalendar -> new FreeStaff(staffCalendar.staffId(), staffCalendar.durationMinutes()))
			.sorted(Comparator.comparing(FreeStaff::staffId))
			.toList();
	}

	private AvailabilityView view(BranchInfo branch, BookingRequirements requirements, LocalDate from, LocalDate to,
			UUID staffId, Duration minNotice, int maxAdvanceDays) {
		ZoneId zone = branch.timeZone();
		List<StaffInfo> staff = candidates(branch, requirements, staffId);
		List<StaffCalendar> staffCalendars = staffCalendars(branch, requirements, staff,
				from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(), null);

		var days = AvailabilityEngine.compute(new AvailabilityEngine.Query(zone, clock.instant(), from, to,
				branch.slotIntervalMinutes(), minNotice, maxAdvanceDays, requirements.bufferMinutes(),
				calendars.load(branch.salonId(), branch.id(), from, to), staffCalendars));

		return new AvailabilityView(branch.id(), zone.getId(),
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

	/** Active, bookable, assigned to the branch and skilled in every requested service. */
	private List<StaffInfo> candidates(BranchInfo branch, BookingRequirements requirements, UUID staffId) {
		return directory.listStaff(branch.salonId())
			.stream()
			.filter(member -> member.isBookableAt(branch.id()))
			.filter(member -> requirements.durationMinutesByStaff().containsKey(member.id()))
			.filter(member -> staffId == null || member.id().equals(staffId))
			.toList();
	}

	/** Working hours at the branch, and time off plus blocking appointments overlapping the window. */
	private List<StaffCalendar> staffCalendars(BranchInfo branch, BookingRequirements requirements,
			List<StaffInfo> staff, Instant windowStart, Instant windowEnd, UUID ignoredAppointmentId) {
		if (staff.isEmpty()) {
			return List.of();
		}
		UUID salonId = branch.salonId();
		List<UUID> staffIds = staff.stream().map(StaffInfo::id).toList();
		Map<UUID, List<WorkingPeriod>> periodsByStaff = periods
			.findBySalonIdAndBranchIdAndStaffIdIn(salonId, branch.id(), staffIds)
			.stream()
			.collect(Collectors.groupingBy(WorkingPeriod::getStaffId));
		Map<UUID, List<InstantRange>> absencesByStaff = timeOffs
			.findBySalonIdAndStaffIdInAndEndAtAfterAndStartAtBefore(salonId, staffIds, windowStart, windowEnd)
			.stream()
			.collect(Collectors.groupingBy(TimeOff::getStaffId,
					Collectors.mapping(TimeOff::range, Collectors.toList())));
		Map<UUID, List<InstantRange>> busyByStaff = blocking(salonId, staffIds, windowStart, windowEnd)
			.stream()
			.filter(appointment -> !appointment.id().equals(ignoredAppointmentId))
			.collect(Collectors.groupingBy(BlockingAppointment::staffId, Collectors.mapping(
					appointment -> new InstantRange(appointment.start(), appointment.blockEnd()), Collectors.toList())));

		return staff.stream()
			.map(member -> new StaffCalendar(member.id(), requirements.durationMinutesByStaff().get(member.id()),
					periodsByStaff.getOrDefault(member.id(), List.of()).stream().map(WorkingPeriod::pattern).toList(),
					concat(absencesByStaff.get(member.id()), busyByStaff.get(member.id()))))
			.toList();
	}

	private List<BlockingAppointment> blocking(UUID salonId, Collection<UUID> staffIds, Instant from, Instant to) {
		return appointments.stream()
			.flatMap(source -> source.ofStaffBetween(salonId, staffIds, from, to).stream())
			.toList();
	}

	private static List<InstantRange> concat(List<InstantRange> first, List<InstantRange> second) {
		return Stream.of(first, second)
			.filter(Objects::nonNull)
			.flatMap(List::stream)
			.toList();
	}

	/** The booker's rules for one exact start (ADR-0013, decisions 37 and 40). */
	private void checkStart(BranchInfo branch, Instant start, Booker booker) {
		if (start.getNano() != 0 || start.getEpochSecond() % 60 != 0) {
			throw SchedulingErrors.invalidStart("Appointments start on a whole minute.");
		}
		Instant now = clock.instant();
		ZoneId zone = branch.timeZone();
		LocalDate today = LocalDate.ofInstant(now, zone);
		LocalDate date = LocalDate.ofInstant(start, zone);
		if (booker == Booker.CUSTOMER) {
			int minuteOfDay = start.atZone(zone).toLocalTime().toSecondOfDay() / 60;
			if (minuteOfDay % branch.slotIntervalMinutes() != 0) {
				throw SchedulingErrors.invalidStart(
						"Start times are on the branch's " + branch.slotIntervalMinutes() + "-minute grid.");
			}
			if (start.isBefore(now.plus(Duration.ofMinutes(branch.minNoticeMinutes())))) {
				throw SchedulingErrors.invalidStart(
						"Bookings need at least " + branch.minNoticeMinutes() + " minutes' notice.");
			}
			if (date.isAfter(today.plusDays(branch.maxAdvanceDays()))) {
				throw SchedulingErrors.invalidStart(
						"Bookings are accepted at most " + branch.maxAdvanceDays() + " days ahead.");
			}
		}
		else {
			if (start.isBefore(now.minus(BookableSlots.SALON_PAST_TOLERANCE))) {
				throw SchedulingErrors.invalidStart("Salon bookings can start at most 15 minutes in the past.");
			}
			if (date.isAfter(today.plusDays(BookableSlots.SALON_MAX_ADVANCE_DAYS))) {
				throw SchedulingErrors.invalidStart("Salon bookings are accepted at most 365 days ahead.");
			}
		}
	}

	private static void checkQuery(List<UUID> serviceIds, LocalDate from, LocalDate to) {
		if (serviceIds == null || serviceIds.isEmpty() || serviceIds.size() > MAX_SERVICES) {
			throw SchedulingErrors.invalidServices(MAX_SERVICES);
		}
		if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
			throw SchedulingErrors.invalidDateRange("The range must be ordered and at most " + MAX_DAYS + " days long.");
		}
	}

}
