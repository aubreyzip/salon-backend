package com.salon.scheduling.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.scheduling.application.SchedulingViews.TimeOffView;
import com.salon.scheduling.application.TimeOffService;
import com.salon.salons.ConflictOverride;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff time off, as instants (ISO-8601 with offset). Effective immediately.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/staff/{staffId}/time-off")
class TimeOffController {

	private final TimeOffService timeOff;

	TimeOffController(TimeOffService timeOff) {
		this.timeOff = timeOff;
	}

	/** Time off overlapping {@code [from, to)}; defaults: from now, for one year. */
	@GetMapping
	List<TimeOffView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to) {
		return timeOff.list(salonId, staffId, userId, from, to);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TimeOffView create(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId,
			@Valid @RequestBody TimeOffRequest request) {
		return timeOff.create(salonId, staffId, userId, request.startAt(), request.endAt(),
				request.reason() == null || request.reason().isBlank() ? null : request.reason().strip(),
				request.conflictOverride());
	}

	@DeleteMapping("/{timeOffId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId,
			@PathVariable UUID timeOffId) {
		timeOff.delete(salonId, staffId, timeOffId, userId);
	}

	record TimeOffRequest(@NotNull Instant startAt, @NotNull Instant endAt, @Size(max = 200) String reason,
			@Valid ConflictOverride conflictOverride) {
	}

}
