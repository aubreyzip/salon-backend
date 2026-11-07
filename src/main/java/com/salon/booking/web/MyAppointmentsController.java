package com.salon.booking.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.booking.application.BookingCommands.BookOnline;
import com.salon.booking.application.BookingCommands.Reschedule;
import com.salon.booking.application.BookingViews.CustomerAppointmentView;
import com.salon.booking.application.CustomerAppointmentService;
import com.salon.booking.application.CustomerAppointmentService.Scope;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's appointments (ADR-0013). Booking and rescheduling require an
 * {@code Idempotency-Key} header; a retry with the same key returns the same appointment.
 */
@RestController
@RequestMapping("/api/v1/me/appointments")
class MyAppointmentsController {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	private final CustomerAppointmentService appointments;

	MyAppointmentsController(CustomerAppointmentService appointments) {
		this.appointments = appointments;
	}

	/**
	 * Books services with one staff member at a public branch. {@code CONFIRMED} or, if the branch
	 * confirms manually, {@code PENDING} until the salon confirms. Omit {@code staffId} for anyone
	 * available.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CustomerAppointmentView book(@CurrentUserId UUID userId,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@Valid @RequestBody BookRequest request) {
		return appointments.book(userId, idempotencyKey, new BookOnline(request.branchId(), request.serviceIds(),
				request.staffId(), request.start(), Texts.blankToNull(request.customerNote())));
	}

	/** {@code UPCOMING} (default): pending or confirmed, soonest first; {@code PAST}: everything else. */
	@GetMapping
	Page<CustomerAppointmentView> list(@CurrentUserId UUID userId,
			@RequestParam(defaultValue = "UPCOMING") Scope scope, @PageableDefault(size = 20) Pageable pageable) {
		return appointments.list(userId, scope, pageable);
	}

	@GetMapping("/{appointmentId}")
	CustomerAppointmentView get(@CurrentUserId UUID userId, @PathVariable UUID appointmentId) {
		return appointments.get(userId, appointmentId);
	}

	/** Until the branch's cancellation cutoff (default 2 hours before the start). */
	@PostMapping("/{appointmentId}/cancel")
	CustomerAppointmentView cancel(@CurrentUserId UUID userId, @PathVariable UUID appointmentId,
			@Valid @RequestBody(required = false) CustomerCancelRequest request) {
		return appointments.cancel(userId, appointmentId,
				request == null ? null : Texts.blankToNull(request.reason()));
	}

	/**
	 * Moves the appointment (same services) until the cancellation cutoff. Omit {@code staffId} to
	 * keep the staff member.
	 */
	@PostMapping("/{appointmentId}/reschedule")
	CustomerAppointmentView reschedule(@CurrentUserId UUID userId, @PathVariable UUID appointmentId,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@Valid @RequestBody RescheduleRequest request) {
		return appointments.reschedule(userId, appointmentId, idempotencyKey,
				new Reschedule(request.start(), request.staffId()));
	}

	record BookRequest(@NotNull UUID branchId, @NotNull @Size(min = 1, max = 5) List<@NotNull UUID> serviceIds,
			UUID staffId, @NotNull Instant start, @Size(max = 500) String customerNote) {
	}

	record CustomerCancelRequest(@Size(max = 500) String reason) {
	}

	record RescheduleRequest(@NotNull Instant start, UUID staffId) {
	}

}
