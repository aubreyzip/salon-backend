package com.salon.scheduling.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.persistence.Embeddable;

/**
 * A local time interval within one day, {@code [startTime, endTime)}, in the branch's time zone.
 * Whole minutes only; no overnight intervals (ADR-0012).
 */
@Embeddable
public record TimeInterval(LocalTime startTime, LocalTime endTime) {

	public static final Comparator<TimeInterval> BY_START = Comparator.comparing(TimeInterval::startTime);

	public TimeInterval {
		if (startTime == null || endTime == null || !startTime.isBefore(endTime) || !wholeMinute(startTime)
				|| !wholeMinute(endTime)) {
			throw SchedulingErrors.invalidInterval();
		}
	}

	public static TimeInterval of(String start, String end) {
		return new TimeInterval(LocalTime.parse(start), LocalTime.parse(end));
	}

	public boolean overlaps(TimeInterval other) {
		return startTime.isBefore(other.endTime) && other.startTime.isBefore(endTime);
	}

	/** Sorted by start, or {@code OVERLAPPING_INTERVALS} if any two overlap (touching is fine). */
	public static List<TimeInterval> sortedNonOverlapping(List<TimeInterval> intervals) {
		List<TimeInterval> sorted = intervals.stream().sorted(BY_START).toList();
		for (int i = 1; i < sorted.size(); i++) {
			if (sorted.get(i - 1).overlaps(sorted.get(i))) {
				throw SchedulingErrors.overlappingIntervals();
			}
		}
		return sorted;
	}

	/** Intersection of two sorted, non-overlapping interval lists. */
	public static List<TimeInterval> intersect(List<TimeInterval> a, List<TimeInterval> b) {
		List<TimeInterval> result = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < a.size() && j < b.size()) {
			TimeInterval x = a.get(i);
			TimeInterval y = b.get(j);
			LocalTime start = x.startTime.isAfter(y.startTime) ? x.startTime : y.startTime;
			LocalTime end = x.endTime.isBefore(y.endTime) ? x.endTime : y.endTime;
			if (start.isBefore(end)) {
				result.add(new TimeInterval(start, end));
			}
			if (x.endTime.isBefore(y.endTime)) {
				i++;
			}
			else {
				j++;
			}
		}
		return result;
	}

	private static boolean wholeMinute(LocalTime time) {
		return time.getSecond() == 0 && time.getNano() == 0;
	}

}
