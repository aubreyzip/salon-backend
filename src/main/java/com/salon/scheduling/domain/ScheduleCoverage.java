package com.salon.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Whether schedules still cover an appointment (ADR-0012, decision 21). Pure functions, like the
 * availability engine. An appointment occupies {@code [start, blockEnd)} on the local date of its
 * start; there are no overnight intervals.
 */
public final class ScheduleCoverage {

	private ScheduleCoverage() {
	}

	/** Whether {@code [start, end)} fits into one of the local intervals of the start's date. */
	public static boolean covers(List<TimeInterval> intervals, ZoneId zone, Instant start, Instant end) {
		LocalDate date = LocalDate.ofInstant(start, zone);
		return AvailabilityEngine.toInstants(intervals, date, zone)
			.stream()
			.anyMatch(range -> range.contains(start, end));
	}

	/**
	 * A change takes away time the appointment needs: the appointment fits the hours before the
	 * change and not after it. Appointments that did not fit before (e.g. booked with an override)
	 * are not blamed on an unrelated change.
	 */
	public static boolean losesCoverage(List<TimeInterval> before, List<TimeInterval> after, ZoneId zone,
			Instant start, Instant end) {
		return covers(before, zone, start, end) && !covers(after, zone, start, end);
	}

}
