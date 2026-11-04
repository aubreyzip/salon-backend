package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salon.scheduling.domain.AvailabilityEngine.BranchCalendar;
import com.salon.scheduling.domain.AvailabilityEngine.DayAvailability;
import com.salon.scheduling.domain.AvailabilityEngine.Slot;
import com.salon.scheduling.domain.AvailabilityEngine.StaffCalendar;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The availability engine as a pure function. Default fixture: Istanbul, Monday 2026-11-16, branch
 * open 09:00–18:00 every day, 30-minute grid, no notice, 60 days advance, "now" a week earlier.
 */
class AvailabilityEngineTest {

	private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

	private static final LocalDate MONDAY_16 = LocalDate.of(2026, 11, 16);

	private static final Instant A_WEEK_EARLIER = Instant.parse("2026-11-09T06:00:00Z");

	private static final UUID AYSE = UUID.fromString("00000000-0000-7000-8000-000000000001");

	private static final UUID MEHMET = UUID.fromString("00000000-0000-7000-8000-000000000002");

	private final Fixture fixture = new Fixture();

	@Test
	void offersGridStartsWhereTheWholeBookingFitsIntoWorkingHours() {
		fixture.staff(AYSE, 60, everyDay("10:00", "14:00"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("10:00", "10:30", "11:00", "11:30", "12:00", "12:30",
				"13:00");
	}

	@Test
	void theBufferMustAlsoFitBeforeTheEndOfWork() {
		fixture.bufferMinutes = 15;
		fixture.staff(AYSE, 60, everyDay("10:00", "14:00"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("10:00", "10:30", "11:00", "11:30", "12:00",
				"12:30");
	}

	@Test
	void bookingsDoNotSpanABreakBetweenIntervals() {
		fixture.staff(AYSE, 60, pattern(MONDAY, "09:00", "12:00", "13:00", "15:00"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "13:00",
				"13:30", "14:00");
	}

	@Test
	void workingHoursAreLimitedToOpeningHours() {
		fixture.openEveryDay("10:00", "12:00");
		fixture.staff(AYSE, 60, everyDay("08:00", "20:00"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("10:00", "10:30", "11:00");
	}

	@Test
	void startsAreAlignedToTheClockNotToTheStartOfWork() {
		fixture.slotIntervalMinutes = 15;
		fixture.staff(AYSE, 30, everyDay("09:10", "10:30"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("09:15", "09:30", "09:45", "10:00");
	}

	@Test
	void timeOffRemovesOverlappingStarts() {
		fixture.staff(AYSE, 60, everyDay("10:00", "14:00"));
		fixture.absence(AYSE, at(MONDAY_16, "11:00"), at(MONDAY_16, "12:00"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("10:00", "12:00", "12:30", "13:00");
	}

	@Test
	void multiDayTimeOffBlocksWholeDays() {
		fixture.staff(AYSE, 60, everyDay("10:00", "14:00"));
		fixture.absence(AYSE, at(MONDAY_16.minusDays(1), "00:00"), at(MONDAY_16.plusDays(1), "00:00"));

		assertThat(fixture.startTimes(MONDAY_16)).isEmpty();
		assertThat(fixture.startTimes(MONDAY_16.plusDays(1))).isNotEmpty();
	}

	@Test
	void aSpecialDayReplacesTheWeeklyOpeningHours() {
		fixture.specialDays.put(MONDAY_16, List.of(TimeInterval.of("09:00", "11:00")));
		fixture.staff(AYSE, 60, everyDay("09:00", "18:00"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("09:00", "09:30", "10:00");
	}

	@Test
	void aSpecialDayWithoutIntervalsIsClosed() {
		fixture.specialDays.put(MONDAY_16, List.of());
		fixture.staff(AYSE, 60, everyDay("09:00", "18:00"));

		assertThat(fixture.startTimes(MONDAY_16)).isEmpty();
	}

	@Test
	void closuresWinOverSpecialDays() {
		fixture.specialDays.put(MONDAY_16, List.of(TimeInterval.of("09:00", "11:00")));
		fixture.closures.add(new DateRange(MONDAY_16.minusDays(2), MONDAY_16));
		fixture.staff(AYSE, 60, everyDay("09:00", "18:00"));

		assertThat(fixture.startTimes(MONDAY_16)).isEmpty();
		assertThat(fixture.startTimes(MONDAY_16.plusDays(1))).isNotEmpty();
	}

	@Test
	void aBranchWithoutOpeningHoursHasNoAvailability() {
		fixture.openingHours.clear();
		fixture.staff(AYSE, 60, everyDay("09:00", "18:00"));

		assertThat(fixture.startTimes(MONDAY_16)).isEmpty();
	}

	@Test
	void onlyThePeriodEffectiveOnTheDateApplies() {
		WorkingPattern untilSunday = new WorkingPattern(new DateRange(LocalDate.of(2026, 1, 1), MONDAY_16.minusDays(1)),
				hours(MONDAY, "09:00", "18:00"));
		WorkingPattern fromMonday = new WorkingPattern(new DateRange(MONDAY_16, null), hours(MONDAY, "15:00", "17:00"));
		fixture.staff.add(new StaffCalendar(AYSE, 60, List.of(untilSunday, fromMonday), List.of()));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("15:00", "15:30", "16:00");
		assertThat(fixture.startTimes(MONDAY_16.minusDays(7))).hasSize(17);
	}

	@Test
	void staffWithoutHoursOnThatWeekdayAreNotOffered() {
		fixture.staff(AYSE, 60, pattern(TUESDAY, "09:00", "18:00"));

		assertThat(fixture.startTimes(MONDAY_16)).isEmpty();
		assertThat(fixture.startTimes(MONDAY_16.plusDays(1))).isNotEmpty();
	}

	@Test
	void anyStaffIsTheUnionWithEachStaffMembersOwnDuration() {
		fixture.staff(AYSE, 60, everyDay("10:00", "12:00"));
		fixture.staff(MEHMET, 90, everyDay("11:00", "13:00"));

		List<Slot> slots = fixture.slots(MONDAY_16);

		assertThat(slots).extracting(slot -> slot.localTime().toString())
			.containsExactly("10:00", "10:30", "11:00", "11:30");
		assertThat(slots).extracting(Slot::staffIds)
			.containsExactly(List.of(AYSE), List.of(AYSE), List.of(AYSE, MEHMET), List.of(MEHMET));
	}

	@Test
	void startsBeforeTheMinimumNoticeAreNotOffered() {
		fixture.now = at(MONDAY_16, "09:20");
		fixture.minNotice = Duration.ofMinutes(60);
		fixture.staff(AYSE, 30, everyDay("09:00", "12:00"));

		assertThat(fixture.startTimes(MONDAY_16)).containsExactly("10:30", "11:00", "11:30");
	}

	@Test
	void daysBeyondTheMaximumAdvanceAreEmpty() {
		fixture.now = at(MONDAY_16, "08:00");
		fixture.maxAdvanceDays = 1;
		fixture.staff(AYSE, 60, everyDay("09:00", "12:00"));

		List<DayAvailability> days = fixture.compute(MONDAY_16, MONDAY_16.plusDays(2));

		assertThat(days).extracting(DayAvailability::date)
			.containsExactly(MONDAY_16, MONDAY_16.plusDays(1), MONDAY_16.plusDays(2));
		assertThat(days).extracting(day -> day.slots().isEmpty()).containsExactly(false, false, true);
	}

	@Test
	void everyDayOfTheRangeIsReturnedEvenWithoutStaff() {
		List<DayAvailability> days = fixture.compute(MONDAY_16, MONDAY_16.plusDays(6));

		assertThat(days).hasSize(7).allSatisfy(day -> assertThat(day.slots()).isEmpty());
	}

	@Test
	void slotsCarryTheInstantInUtc() {
		fixture.staff(AYSE, 60, everyDay("10:00", "11:00"));

		assertThat(fixture.slots(MONDAY_16)).extracting(Slot::start)
			.containsExactly(Instant.parse("2026-11-16T07:00:00Z"));
	}

	@Nested
	class DaylightSavingTime {

		private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

		@Test
		void localTimesSkippedBySpringForwardAreNotOffered() {
			LocalDate springForward = LocalDate.of(2026, 3, 29);
			fixture.zone = BERLIN;
			fixture.now = Instant.parse("2026-03-01T00:00:00Z");
			fixture.openEveryDay("01:00", "04:00");
			fixture.staff(AYSE, 30, everyDay("01:00", "04:00"));

			List<Slot> slots = fixture.slots(springForward);

			assertThat(slots).extracting(slot -> slot.localTime().toString())
				.containsExactly("01:00", "01:30", "03:00", "03:30");
			// 01:00-04:00 local is only two real hours that night.
			assertThat(slots).extracting(Slot::start)
				.containsExactly(Instant.parse("2026-03-29T00:00:00Z"), Instant.parse("2026-03-29T00:30:00Z"),
						Instant.parse("2026-03-29T01:00:00Z"), Instant.parse("2026-03-29T01:30:00Z"));
		}

		@Test
		void repeatedLocalTimesOnFallBackAreOfferedOnceAtTheEarlierInstant() {
			LocalDate fallBack = LocalDate.of(2026, 10, 25);
			fixture.zone = BERLIN;
			fixture.now = Instant.parse("2026-10-01T00:00:00Z");
			fixture.openEveryDay("01:00", "04:00");
			fixture.staff(AYSE, 30, everyDay("01:00", "04:00"));

			List<Slot> slots = fixture.slots(fallBack);

			assertThat(slots).extracting(slot -> slot.localTime().toString())
				.containsExactly("01:00", "01:30", "02:00", "02:30", "03:00", "03:30");
			assertThat(slots.get(2).start()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
			assertThat(slots).extracting(Slot::start).isSorted();
		}

		@Test
		void aBookingMayRunAcrossTheFallBackHour() {
			LocalDate fallBack = LocalDate.of(2026, 10, 25);
			fixture.zone = BERLIN;
			fixture.now = Instant.parse("2026-10-01T00:00:00Z");
			fixture.openEveryDay("02:00", "03:30");
			fixture.staff(AYSE, 120, everyDay("02:00", "03:30"));

			// 02:00 CEST to 03:30 CET is two and a half real hours.
			assertThat(fixture.startTimes(fallBack)).containsExactly("02:00", "02:30");
		}

	}

	private Instant at(LocalDate date, String time) {
		return ZonedDateTime.of(date, LocalTime.parse(time), fixture.zone).toInstant();
	}

	private static WorkingPattern everyDay(String start, String end) {
		Map<DayOfWeek, List<TimeInterval>> hours = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek day = MONDAY;; day = day.plus(1)) {
			hours.put(day, List.of(TimeInterval.of(start, end)));
			if (day == SUNDAY) {
				break;
			}
		}
		return new WorkingPattern(new DateRange(LocalDate.of(2026, 1, 1), null), hours);
	}

	private static WorkingPattern pattern(DayOfWeek day, String... times) {
		return new WorkingPattern(new DateRange(LocalDate.of(2026, 1, 1), null), hours(day, times));
	}

	private static Map<DayOfWeek, List<TimeInterval>> hours(DayOfWeek day, String... times) {
		List<TimeInterval> intervals = new ArrayList<>();
		for (int i = 0; i < times.length; i += 2) {
			intervals.add(TimeInterval.of(times[i], times[i + 1]));
		}
		return Map.of(day, intervals);
	}

	/** Mutable query inputs with the defaults described on the class. */
	private static final class Fixture {

		ZoneId zone = ISTANBUL;

		Instant now = A_WEEK_EARLIER;

		int slotIntervalMinutes = 30;

		Duration minNotice = Duration.ZERO;

		int maxAdvanceDays = 60;

		int bufferMinutes = 0;

		final Map<DayOfWeek, List<TimeInterval>> openingHours = new EnumMap<>(DayOfWeek.class);

		final Map<LocalDate, List<TimeInterval>> specialDays = new HashMap<>();

		final List<DateRange> closures = new ArrayList<>();

		final List<StaffCalendar> staff = new ArrayList<>();

		private final Map<UUID, List<InstantRange>> absences = new HashMap<>();

		Fixture() {
			openEveryDay("09:00", "18:00");
		}

		void openEveryDay(String start, String end) {
			openingHours.clear();
			for (DayOfWeek day : DayOfWeek.values()) {
				openingHours.put(day, List.of(TimeInterval.of(start, end)));
			}
		}

		void staff(UUID id, int durationMinutes, WorkingPattern pattern) {
			staff.add(new StaffCalendar(id, durationMinutes, List.of(pattern), List.of()));
		}

		void absence(UUID id, Instant start, Instant end) {
			absences.computeIfAbsent(id, key -> new ArrayList<>()).add(new InstantRange(start, end));
		}

		List<DayAvailability> compute(LocalDate from, LocalDate to) {
			List<StaffCalendar> calendars = staff.stream()
				.map(s -> new StaffCalendar(s.staffId(), s.durationMinutes(), s.patterns(),
						absences.getOrDefault(s.staffId(), List.of())))
				.toList();
			return AvailabilityEngine.compute(new AvailabilityEngine.Query(zone, now, from, to, slotIntervalMinutes,
					minNotice, maxAdvanceDays, bufferMinutes, new BranchCalendar(openingHours, specialDays, closures),
					calendars));
		}

		List<Slot> slots(LocalDate date) {
			return compute(date, date).getFirst().slots();
		}

		List<String> startTimes(LocalDate date) {
			return slots(date).stream().map(slot -> slot.localTime().toString()).toList();
		}

	}

}
