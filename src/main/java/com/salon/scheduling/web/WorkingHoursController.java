package com.salon.scheduling.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.salon.scheduling.application.SchedulingViews.WorkingHoursView;
import com.salon.scheduling.application.WorkingHoursService;
import com.salon.scheduling.application.WorkingHoursService.PeriodInput;
import com.salon.scheduling.domain.DateRange;
import com.salon.scheduling.web.IntervalRequests.WeeklyIntervalRequest;
import com.salon.salons.ConflictOverride;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff working hours per branch: weekly intervals valid within a date range.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/staff/{staffId}")
class WorkingHoursController {

	private final WorkingHoursService workingHours;

	WorkingHoursController(WorkingHoursService workingHours) {
		this.workingHours = workingHours;
	}

	/** The staff member's working hours in all branches visible to the caller. */
	@GetMapping("/working-hours")
	List<WorkingHoursView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId) {
		return workingHours.list(salonId, staffId, userId);
	}

	/**
	 * Replaces the staff member's working hours at the branch. Periods must not overlap in dates;
	 * hours must not overlap with the staff member's hours at other branches.
	 */
	@PutMapping("/branches/{branchId}/working-hours")
	WorkingHoursView replace(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId,
			@PathVariable UUID branchId, @Valid @RequestBody WorkingHoursRequest request) {
		return workingHours.replace(salonId, staffId, branchId, userId,
				request.periods().stream().map(PeriodRequest::toInput).toList(), request.conflictOverride());
	}

	record WorkingHoursRequest(@NotNull @Size(max = 20) List<@NotNull @Valid PeriodRequest> periods,
			@Valid ConflictOverride conflictOverride) {
	}

	/**
	 * @param effectiveTo inclusive; omit for open-ended
	 */
	record PeriodRequest(@NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
			@NotNull @Size(max = 50) List<@NotNull @Valid WeeklyIntervalRequest> intervals) {

		PeriodInput toInput() {
			return new PeriodInput(new DateRange(effectiveFrom, effectiveTo),
					intervals.stream().map(WeeklyIntervalRequest::toInterval).toList());
		}

	}

}
