package com.salon.scheduling.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleCoverageTest {

	private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

	/** 2026-11-16 11:00–12:15 in Istanbul. */
	private static final Instant START = Instant.parse("2026-11-16T08:00:00Z");

	private static final Instant END = Instant.parse("2026-11-16T09:15:00Z");

	@Test
	void anAppointmentIsCoveredWhenItFitsIntoOneInterval() {
		assertThat(ScheduleCoverage.covers(List.of(TimeInterval.of("09:00", "12:15")), ISTANBUL, START, END)).isTrue();
		assertThat(ScheduleCoverage.covers(List.of(TimeInterval.of("09:00", "12:00")), ISTANBUL, START, END)).isFalse();
		// Two touching intervals do not form one free range for a booking (same as the engine).
		assertThat(ScheduleCoverage.covers(List.of(TimeInterval.of("09:00", "11:30"), TimeInterval.of("11:30", "18:00")),
				ISTANBUL, START, END))
			.isFalse();
		assertThat(ScheduleCoverage.covers(List.of(), ISTANBUL, START, END)).isFalse();
	}

	@Test
	void onlyChangesThatRemoveCoverageConflict() {
		List<TimeInterval> wide = List.of(TimeInterval.of("09:00", "18:00"));
		List<TimeInterval> narrow = List.of(TimeInterval.of("09:00", "11:00"));

		assertThat(ScheduleCoverage.losesCoverage(wide, narrow, ISTANBUL, START, END)).isTrue();
		assertThat(ScheduleCoverage.losesCoverage(narrow, wide, ISTANBUL, START, END)).isFalse();
		// Already uncovered before the change: not this change's fault.
		assertThat(ScheduleCoverage.losesCoverage(narrow, List.of(), ISTANBUL, START, END)).isFalse();
	}

}
