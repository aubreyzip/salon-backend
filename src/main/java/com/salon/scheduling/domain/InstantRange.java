package com.salon.scheduling.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A half-open range of moments, {@code [start, end)}.
 */
public record InstantRange(Instant start, Instant end) {

	public InstantRange {
		if (!start.isBefore(end)) {
			throw new IllegalArgumentException("start must be before end");
		}
	}

	public boolean overlaps(InstantRange other) {
		return start.isBefore(other.end) && other.start.isBefore(end);
	}

	public boolean contains(Instant from, Instant to) {
		return !from.isBefore(start) && !to.isAfter(end);
	}

	/** {@code ranges} minus {@code removed}; the result is sorted. */
	public static List<InstantRange> subtract(List<InstantRange> ranges, List<InstantRange> removed) {
		List<InstantRange> result = new ArrayList<>(ranges);
		for (InstantRange cut : removed) {
			List<InstantRange> next = new ArrayList<>();
			for (InstantRange range : result) {
				if (!range.overlaps(cut)) {
					next.add(range);
					continue;
				}
				if (range.start.isBefore(cut.start)) {
					next.add(new InstantRange(range.start, cut.start));
				}
				if (cut.end.isBefore(range.end)) {
					next.add(new InstantRange(cut.end, range.end));
				}
			}
			result = next;
		}
		result.sort(Comparator.comparing(InstantRange::start));
		return result;
	}

}
