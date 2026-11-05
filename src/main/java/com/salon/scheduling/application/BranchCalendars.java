package com.salon.scheduling.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salon.scheduling.domain.AvailabilityEngine.BranchCalendar;
import com.salon.scheduling.domain.BranchClosure;
import com.salon.scheduling.domain.BranchClosureRepository;
import com.salon.scheduling.domain.BranchHours;
import com.salon.scheduling.domain.BranchHoursRepository;
import com.salon.scheduling.domain.SpecialDay;
import com.salon.scheduling.domain.SpecialDayRepository;
import com.salon.scheduling.domain.TimeInterval;
import org.springframework.stereotype.Component;

/**
 * Loads a branch's opening hours, special days and closures for a date range.
 */
@Component
class BranchCalendars {

	private final BranchHoursRepository hours;

	private final SpecialDayRepository specialDays;

	private final BranchClosureRepository closures;

	BranchCalendars(BranchHoursRepository hours, SpecialDayRepository specialDays, BranchClosureRepository closures) {
		this.hours = hours;
		this.specialDays = specialDays;
		this.closures = closures;
	}

	/** The calendar as needed for dates from {@code from} to {@code to} (inclusive). */
	BranchCalendar load(UUID salonId, UUID branchId, LocalDate from, LocalDate to) {
		var openingHours = hours.findBySalonIdAndBranchId(salonId, branchId)
			.map(BranchHours::byDay)
			.orElse(Map.of());
		Map<LocalDate, List<TimeInterval>> special = specialDays
			.findBySalonIdAndBranchIdAndOnDateBetweenOrderByOnDateAsc(salonId, branchId, from, to)
			.stream()
			.collect(Collectors.toMap(SpecialDay::getOnDate, SpecialDay::getIntervals));
		var closed = closures
			.findBySalonIdAndBranchIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(salonId, branchId, to, from)
			.stream()
			.map(BranchClosure::dates)
			.toList();
		return new BranchCalendar(openingHours, special, closed);
	}

}
