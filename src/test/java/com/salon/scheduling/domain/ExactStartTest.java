package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salon.scheduling.domain.AvailabilityEngine.BranchCalendar;
import com.salon.scheduling.domain.AvailabilityEngine.StaffCalendar;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AvailabilityEngine#isFree}: one exact start, as booking checks it (ADR-0013). Any minute,
 * no grid; the services plus the buffer must fit into opening ∩ working hours minus absences.
 * Istanbul, branch open 09:00–18:00 every day, staff working 10:00–14:00.
 */
class ExactStartTest {

	private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

	private static final LocalDate DAY = LocalDate.of(2026, 11, 16);

	private static final BranchCalendar OPEN = new BranchCalendar(everyDay("09:00", "18:00"), Map.of(), List.of());

	@Test
	void anyMinuteInsideWorkingHoursFits() {
		assertThat(free(staff(60), "10:07", 0)).isTrue();
		assertThat(free(staff(60), "13:00", 0)).isTrue();
		assertThat(free(staff(60), "13:01", 0)).isFalse();
		assertThat(free(staff(60), "09:59", 0)).isFalse();
	}

	@Test
	void theBufferMustFitToo() {
		assertThat(free(staff(60), "12:45", 15)).isTrue();
		assertThat(free(staff(60), "12:46", 15)).isFalse();
	}

	@Test
	void absencesSuchAsAppointmentsBlockAndBackToBackFits() {
		StaffCalendar busy = new StaffCalendar(UUID.randomUUID(), 60,
				List.of(new WorkingPattern(new DateRange(DAY.minusDays(7), null), everyDay("10:00", "14:00"))),
				List.of(new InstantRange(at("11:00"), at("12:00"))));

		assertThat(free(busy, "10:00", 0)).isTrue();
		assertThat(free(busy, "10:01", 0)).isFalse();
		assertThat(free(busy, "12:00", 0)).isTrue();
	}

	@Test
	void closedDaysHaveNoFreeTime() {
		BranchCalendar closed = OPEN.withClosure(new DateRange(DAY, DAY));

		assertThat(AvailabilityEngine.isFree(closed, staff(60), ISTANBUL, at("10:00"), 0)).isFalse();
	}

	private static boolean free(StaffCalendar staff, String time, int buffer) {
		return AvailabilityEngine.isFree(OPEN, staff, ISTANBUL, at(time), buffer);
	}

	private static StaffCalendar staff(int minutes) {
		return new StaffCalendar(UUID.randomUUID(), minutes,
				List.of(new WorkingPattern(new DateRange(DAY.minusDays(7), null), everyDay("10:00", "14:00"))), List.of());
	}

	private static Instant at(String time) {
		return ZonedDateTime.of(DAY, LocalTime.parse(time), ISTANBUL).toInstant();
	}

	private static List<WeeklyInterval> weekly(String start, String end) {
		return Arrays.stream(DayOfWeek.values())
			.map(day -> new WeeklyInterval(day, LocalTime.parse(start), LocalTime.parse(end)))
			.toList();
	}

	private static Map<DayOfWeek, List<TimeInterval>> everyDay(String start, String end) {
		return WeeklyInterval.byDay(weekly(start, end));
	}

}
