package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Weekly working hours valid in a date range: the input form of a working period, used by the
 * availability engine and the overlap checks.
 */
public record WorkingPattern(DateRange dates, Map<DayOfWeek, List<TimeInterval>> hoursByDay) {

	public WorkingPattern {
		hoursByDay = Map.copyOf(hoursByDay);
	}

	public static WorkingPattern of(DateRange dates, List<WeeklyInterval> intervals) {
		return new WorkingPattern(dates, WeeklyInterval.byDay(intervals));
	}

	public boolean appliesOn(LocalDate date) {
		return dates.contains(date);
	}

	public List<TimeInterval> hoursOn(DayOfWeek day) {
		return hoursByDay.getOrDefault(day, List.of());
	}

	/**
	 * The first day of the week on which both patterns apply on some date and have overlapping
	 * hours, if any.
	 */
	public Optional<DayOfWeek> conflictWith(WorkingPattern other) {
		return dates.intersection(other.dates)
			.flatMap(common -> common.daysOfWeek()
				.stream()
				.filter(day -> !TimeInterval.intersect(hoursOn(day), other.hoursOn(day)).isEmpty())
				.findFirst());
	}

}
