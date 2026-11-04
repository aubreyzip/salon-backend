package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Calendar days from {@code start} to {@code end}, both inclusive. A {@code null} end is
 * open-ended.
 */
public record DateRange(LocalDate start, LocalDate end) {

	public DateRange {
		if (start == null || (end != null && end.isBefore(start))) {
			throw SchedulingErrors.invalidDateRange("The start date must not be after the end date.");
		}
	}

	public boolean contains(LocalDate date) {
		return !date.isBefore(start) && (end == null || !date.isAfter(end));
	}

	public Optional<DateRange> intersection(DateRange other) {
		LocalDate from = start.isAfter(other.start) ? start : other.start;
		LocalDate to = end == null ? other.end : other.end == null ? end : end.isBefore(other.end) ? end : other.end;
		return to != null && to.isBefore(from) ? Optional.empty() : Optional.of(new DateRange(from, to));
	}

	/** The days of the week that occur in this range. */
	public Set<DayOfWeek> daysOfWeek() {
		if (end == null || ChronoUnit.DAYS.between(start, end) >= 6) {
			return EnumSet.allOf(DayOfWeek.class);
		}
		Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			days.add(day.getDayOfWeek());
		}
		return days;
	}

}
