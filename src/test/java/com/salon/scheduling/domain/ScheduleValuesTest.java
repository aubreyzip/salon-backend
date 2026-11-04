package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.salon.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleValuesTest {

	@ParameterizedTest
	@CsvSource({ "10:00, 10:00", "18:00, 09:00", "09:00:30, 10:00", "09:00, 10:00:00.5" })
	void intervalsNeedAStartBeforeTheEndInWholeMinutes(String start, String end) {
		assertThatThrownBy(() -> new TimeInterval(LocalTime.parse(start), LocalTime.parse(end)))
			.isInstanceOf(ApiException.class)
			.extracting("code")
			.isEqualTo("INVALID_INTERVAL");
	}

	@Test
	void touchingIntervalsDoNotOverlap() {
		List<TimeInterval> sorted = TimeInterval.sortedNonOverlapping(
				List.of(TimeInterval.of("13:00", "17:00"), TimeInterval.of("09:00", "13:00")));

		assertThat(sorted).containsExactly(TimeInterval.of("09:00", "13:00"), TimeInterval.of("13:00", "17:00"));
	}

	@Test
	void overlappingIntervalsOfADayAreRejected() {
		List<WeeklyInterval> intervals = List.of(weekly(MONDAY, "09:00", "13:00"), weekly(MONDAY, "12:00", "17:00"),
				weekly(TUESDAY, "09:00", "13:00"));

		assertThatThrownBy(() -> WeeklyInterval.byDay(intervals)).isInstanceOf(ApiException.class)
			.extracting("code")
			.isEqualTo("OVERLAPPING_INTERVALS");
	}

	@Test
	void intersectsSortedIntervalLists() {
		List<TimeInterval> open = List.of(TimeInterval.of("09:00", "12:00"), TimeInterval.of("13:00", "18:00"));
		List<TimeInterval> work = List.of(TimeInterval.of("08:00", "10:00"), TimeInterval.of("11:00", "14:00"));

		assertThat(TimeInterval.intersect(open, work)).containsExactly(TimeInterval.of("09:00", "10:00"),
				TimeInterval.of("11:00", "12:00"), TimeInterval.of("13:00", "14:00"));
	}

	@Test
	void dateRangesIntersectInclusively() {
		DateRange november = new DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

		assertThat(november.intersection(new DateRange(LocalDate.of(2026, 11, 30), null)))
			.contains(new DateRange(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 30)));
		assertThat(november.intersection(new DateRange(LocalDate.of(2026, 12, 1), null))).isEmpty();
		assertThat(new DateRange(LocalDate.of(2026, 1, 1), null).intersection(new DateRange(LocalDate.of(2026, 2, 1), null)))
			.contains(new DateRange(LocalDate.of(2026, 2, 1), null));
	}

	@Test
	void shortDateRangesContainOnlyTheirWeekdays() {
		// 2026-11-20 is a Friday.
		DateRange weekend = new DateRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21));

		assertThat(weekend.daysOfWeek()).containsExactlyInAnyOrder(FRIDAY, SATURDAY);
		assertThat(new DateRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 26)).daysOfWeek())
			.containsExactlyInAnyOrder(DayOfWeek.values());
	}

	@Test
	void endBeforeStartIsRejected() {
		assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)))
			.isInstanceOf(ApiException.class)
			.extracting("code")
			.isEqualTo("INVALID_DATE_RANGE");
	}

	@Test
	void patternsConflictOnlyWhenHoursOverlapOnACommonDate() {
		WorkingPattern mondayMornings = WorkingPattern.of(new DateRange(LocalDate.of(2026, 11, 1), null),
				List.of(weekly(MONDAY, "09:00", "13:00")));

		assertThat(mondayMornings.conflictWith(WorkingPattern.of(new DateRange(LocalDate.of(2026, 1, 1), null),
				List.of(weekly(MONDAY, "12:00", "18:00")))))
			.contains(MONDAY);
		// Back to back is fine.
		assertThat(mondayMornings.conflictWith(WorkingPattern.of(new DateRange(LocalDate.of(2026, 1, 1), null),
				List.of(weekly(MONDAY, "13:00", "18:00")))))
			.isEmpty();
		// Same hours, but the other period ended before this one starts.
		assertThat(mondayMornings.conflictWith(WorkingPattern.of(
				new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31)),
				List.of(weekly(MONDAY, "09:00", "13:00")))))
			.isEmpty();
		// The periods share only Friday 2026-11-20 and Saturday, not a Monday.
		assertThat(WorkingPattern.of(new DateRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21)),
				List.of(weekly(MONDAY, "09:00", "13:00")))
			.conflictWith(mondayMornings)).isEmpty();
	}

	@Test
	void subtractsRangesFromRanges() {
		InstantRange day = new InstantRange(Instant.parse("2026-11-16T06:00:00Z"), Instant.parse("2026-11-16T15:00:00Z"));
		List<InstantRange> removed = List.of(
				new InstantRange(Instant.parse("2026-11-16T08:00:00Z"), Instant.parse("2026-11-16T09:00:00Z")),
				new InstantRange(Instant.parse("2026-11-16T05:00:00Z"), Instant.parse("2026-11-16T06:30:00Z")),
				new InstantRange(Instant.parse("2026-11-16T14:00:00Z"), Instant.parse("2026-11-17T00:00:00Z")));

		assertThat(InstantRange.subtract(List.of(day), removed)).containsExactly(
				new InstantRange(Instant.parse("2026-11-16T06:30:00Z"), Instant.parse("2026-11-16T08:00:00Z")),
				new InstantRange(Instant.parse("2026-11-16T09:00:00Z"), Instant.parse("2026-11-16T14:00:00Z")));
	}

	private static WeeklyInterval weekly(DayOfWeek day, String start, String end) {
		return new WeeklyInterval(day, LocalTime.parse(start), LocalTime.parse(end));
	}

}
