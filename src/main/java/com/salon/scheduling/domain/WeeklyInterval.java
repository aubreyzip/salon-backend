package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.Embeddable;

/**
 * A recurring interval on one day of the week, {@code [startTime, endTime)} in the branch's time
 * zone. Stored as ISO day number + local times (ADR-0006).
 */
@Embeddable
public record WeeklyInterval(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {

	public WeeklyInterval {
		if (dayOfWeek == null) {
			throw SchedulingErrors.invalidInterval();
		}
		new TimeInterval(startTime, endTime);
	}

	public TimeInterval time() {
		return new TimeInterval(startTime, endTime);
	}

	/**
	 * Groups intervals by day, each day sorted.
	 * @throws com.salon.shared.ApiException {@code OVERLAPPING_INTERVALS} if two intervals of a day overlap
	 */
	public static Map<DayOfWeek, List<TimeInterval>> byDay(List<WeeklyInterval> intervals) {
		Map<DayOfWeek, List<TimeInterval>> byDay = new EnumMap<>(DayOfWeek.class);
		intervals.stream()
			.collect(Collectors.groupingBy(WeeklyInterval::dayOfWeek,
					Collectors.mapping(WeeklyInterval::time, Collectors.toList())))
			.forEach((day, times) -> byDay.put(day, TimeInterval.sortedNonOverlapping(times)));
		return byDay;
	}

}
