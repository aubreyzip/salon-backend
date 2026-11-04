package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes bookable start times (report §7, ADR-0012). A pure function over loaded data: no
 * database, no clock, no Spring, so it can be tested exhaustively.
 * <p>
 * Per day and staff member: opening hours (special day, else weekly; none on closure days) ∩ the
 * staff member's working hours, converted to instants in the branch zone, minus absences. A start
 * on the clock-aligned slot grid is offered if {@code duration + buffer} fits into one free range,
 * the start is at least the minimum notice ahead, and the day is within the maximum advance.
 */
public final class AvailabilityEngine {

	private static final int MINUTES_PER_DAY = 24 * 60;

	private AvailabilityEngine() {
	}

	/**
	 * @param zone the branch time zone
	 * @param from first day (inclusive)
	 * @param to last day (inclusive)
	 * @param slotIntervalMinutes the grid; starts are aligned to the clock (e.g. :00, :15, :30, :45)
	 * @param minNotice earliest start relative to {@code now}
	 * @param maxAdvanceDays last bookable day relative to today in the branch zone
	 * @param bufferMinutes blocked after the booking; must also fit into working hours
	 */
	public record Query(ZoneId zone, Instant now, LocalDate from, LocalDate to, int slotIntervalMinutes,
			Duration minNotice, int maxAdvanceDays, int bufferMinutes, BranchCalendar branch,
			List<StaffCalendar> staff) {

		public Query {
			staff = List.copyOf(staff);
		}

	}

	/**
	 * @param openingHours weekly opening hours
	 * @param specialDays dates with their own hours, replacing the weekly hours (empty list = closed)
	 * @param closures whole days the branch is closed
	 */
	public record BranchCalendar(Map<DayOfWeek, List<TimeInterval>> openingHours,
			Map<LocalDate, List<TimeInterval>> specialDays, List<DateRange> closures) {

		public BranchCalendar {
			openingHours = Map.copyOf(openingHours);
			specialDays = Map.copyOf(specialDays);
			closures = List.copyOf(closures);
		}

		public List<TimeInterval> openOn(LocalDate date) {
			if (closures.stream().anyMatch(closure -> closure.contains(date))) {
				return List.of();
			}
			List<TimeInterval> special = specialDays.get(date);
			return special != null ? special : openingHours.getOrDefault(date.getDayOfWeek(), List.of());
		}

	}

	/**
	 * @param durationMinutes how long this staff member takes for the requested services
	 * @param patterns working hours at this branch (non-overlapping date ranges)
	 * @param absences time off and, from Phase 4, blocking appointments
	 */
	public record StaffCalendar(UUID staffId, int durationMinutes, List<WorkingPattern> patterns,
			List<InstantRange> absences) {

		public StaffCalendar {
			patterns = List.copyOf(patterns);
			absences = List.copyOf(absences);
		}

		List<TimeInterval> workingOn(LocalDate date) {
			return patterns.stream()
				.filter(pattern -> pattern.appliesOn(date))
				.findFirst()
				.map(pattern -> pattern.hoursOn(date.getDayOfWeek()))
				.orElse(List.of());
		}

	}

	public record DayAvailability(LocalDate date, List<Slot> slots) {
	}

	/**
	 * @param localTime the start in the branch zone
	 * @param staffIds the staff members free for the whole booking at this start
	 */
	public record Slot(Instant start, LocalTime localTime, List<UUID> staffIds) {
	}

	public static List<DayAvailability> compute(Query query) {
		LocalDate lastBookableDay = LocalDate.ofInstant(query.now(), query.zone()).plusDays(query.maxAdvanceDays());
		Instant earliestStart = query.now().plus(query.minNotice());
		List<DayAvailability> days = new ArrayList<>();
		for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
			List<Slot> slots = date.isAfter(lastBookableDay) ? List.of() : slots(query, date, earliestStart);
			days.add(new DayAvailability(date, slots));
		}
		return days;
	}

	private static List<Slot> slots(Query query, LocalDate date, Instant earliestStart) {
		List<TimeInterval> open = query.branch().openOn(date);
		if (open.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<InstantRange>> freeByStaff = new HashMap<>();
		for (StaffCalendar staff : query.staff()) {
			List<InstantRange> free = InstantRange.subtract(
					toInstants(TimeInterval.intersect(open, staff.workingOn(date)), date, query.zone()),
					staff.absences());
			if (!free.isEmpty()) {
				freeByStaff.put(staff.staffId(), free);
			}
		}
		if (freeByStaff.isEmpty()) {
			return List.of();
		}

		List<Slot> slots = new ArrayList<>();
		for (int minute = 0; minute < MINUTES_PER_DAY; minute += query.slotIntervalMinutes()) {
			LocalTime localTime = LocalTime.of(minute / 60, minute % 60);
			ZonedDateTime zoned = ZonedDateTime.of(date, localTime, query.zone());
			if (!zoned.toLocalTime().equals(localTime)) {
				continue; // does not exist on this day (DST gap)
			}
			Instant start = zoned.toInstant();
			if (start.isBefore(earliestStart)) {
				continue;
			}
			List<UUID> staffIds = new ArrayList<>();
			for (StaffCalendar staff : query.staff()) {
				List<InstantRange> free = freeByStaff.get(staff.staffId());
				Instant end = start.plus(Duration.ofMinutes(staff.durationMinutes() + query.bufferMinutes()));
				if (free != null && free.stream().anyMatch(range -> range.contains(start, end))) {
					staffIds.add(staff.staffId());
				}
			}
			if (!staffIds.isEmpty()) {
				slots.add(new Slot(start, localTime, List.copyOf(staffIds)));
			}
		}
		return slots;
	}

	private static List<InstantRange> toInstants(List<TimeInterval> intervals, LocalDate date, ZoneId zone) {
		List<InstantRange> ranges = new ArrayList<>();
		for (TimeInterval interval : intervals) {
			Instant start = ZonedDateTime.of(date, interval.startTime(), zone).toInstant();
			Instant end = ZonedDateTime.of(date, interval.endTime(), zone).toInstant();
			if (start.isBefore(end)) {
				ranges.add(new InstantRange(start, end));
			}
		}
		return ranges;
	}

}
