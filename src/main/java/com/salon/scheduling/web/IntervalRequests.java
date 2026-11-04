package com.salon.scheduling.web;

import java.time.DayOfWeek;
import java.time.LocalTime;

import com.salon.scheduling.domain.TimeInterval;
import com.salon.scheduling.domain.WeeklyInterval;
import jakarta.validation.constraints.NotNull;

/**
 * Interval request bodies shared by the scheduling controllers. Times are local to the branch
 * ({@code "09:00"}); the domain checks start before end and overlaps.
 */
final class IntervalRequests {

	private IntervalRequests() {
	}

	record WeeklyIntervalRequest(@NotNull DayOfWeek dayOfWeek, @NotNull LocalTime startTime,
			@NotNull LocalTime endTime) {

		WeeklyInterval toInterval() {
			return new WeeklyInterval(dayOfWeek, startTime, endTime);
		}

	}

	record TimeIntervalRequest(@NotNull LocalTime startTime, @NotNull LocalTime endTime) {

		TimeInterval toInterval() {
			return new TimeInterval(startTime, endTime);
		}

	}

}
