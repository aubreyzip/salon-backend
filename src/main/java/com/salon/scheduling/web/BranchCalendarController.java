package com.salon.scheduling.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.salon.scheduling.application.BranchCalendarService;
import com.salon.scheduling.application.SchedulingViews.ClosureView;
import com.salon.scheduling.application.SchedulingViews.OpeningHoursView;
import com.salon.scheduling.application.SchedulingViews.SpecialDayView;
import com.salon.scheduling.domain.DateRange;
import com.salon.scheduling.web.IntervalRequests.TimeIntervalRequest;
import com.salon.scheduling.web.IntervalRequests.WeeklyIntervalRequest;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A branch's opening hours, special days and closures. Times and dates are local to the branch.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/branches/{branchId}")
class BranchCalendarController {

	private final BranchCalendarService calendar;

	BranchCalendarController(BranchCalendarService calendar) {
		this.calendar = calendar;
	}

	@GetMapping("/opening-hours")
	OpeningHoursView openingHours(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID branchId) {
		return calendar.openingHours(salonId, branchId, userId);
	}

	/** Replaces the weekly opening hours. Several intervals per day are allowed; none means closed. */
	@PutMapping("/opening-hours")
	OpeningHoursView replaceOpeningHours(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID branchId, @Valid @RequestBody OpeningHoursRequest request) {
		return calendar.replaceOpeningHours(salonId, branchId, userId,
				request.intervals().stream().map(WeeklyIntervalRequest::toInterval).toList());
	}

	/** Special days from {@code from} (default today) to {@code to} (default one year later). */
	@GetMapping("/special-days")
	List<SpecialDayView> specialDays(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID branchId, @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		return calendar.specialDays(salonId, branchId, userId, from, to);
	}

	/** Sets the hours of one date, replacing the weekly hours. No intervals: closed that day. */
	@PutMapping("/special-days/{date}")
	SpecialDayView putSpecialDay(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId,
			@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date,
			@Valid @RequestBody SpecialDayRequest request) {
		return calendar.putSpecialDay(salonId, branchId, date, userId,
				request.intervals().stream().map(TimeIntervalRequest::toInterval).toList(), blankToNull(request.note()));
	}

	/** The weekly hours apply again on this date. */
	@DeleteMapping("/special-days/{date}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteSpecialDay(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId,
			@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
		calendar.deleteSpecialDay(salonId, branchId, date, userId);
	}

	/** Closures ending on or after {@code from} (default today). */
	@GetMapping("/closures")
	List<ClosureView> closures(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from) {
		return calendar.closures(salonId, branchId, userId, from);
	}

	@PostMapping("/closures")
	@ResponseStatus(HttpStatus.CREATED)
	ClosureView createClosure(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId,
			@Valid @RequestBody ClosureRequest request) {
		return calendar.createClosure(salonId, branchId, userId, new DateRange(request.startDate(), request.endDate()),
				blankToNull(request.reason()));
	}

	@DeleteMapping("/closures/{closureId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteClosure(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId,
			@PathVariable UUID closureId) {
		calendar.deleteClosure(salonId, branchId, closureId, userId);
	}

	record OpeningHoursRequest(@NotNull @Size(max = 50) List<@NotNull @Valid WeeklyIntervalRequest> intervals) {
	}

	record SpecialDayRequest(@NotNull @Size(max = 10) List<@NotNull @Valid TimeIntervalRequest> intervals,
			@Size(max = 200) String note) {
	}

	record ClosureRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate, @Size(max = 200) String reason) {
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
