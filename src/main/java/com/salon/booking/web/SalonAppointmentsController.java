package com.salon.booking.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.booking.application.BookingCommands.BookAtSalon;
import com.salon.booking.application.BookingCommands.Reschedule;
import com.salon.booking.application.BookingViews.SalonAppointmentDetailView;
import com.salon.booking.application.BookingViews.SalonAppointmentView;
import com.salon.booking.application.SalonAppointmentService;
import com.salon.booking.application.SalonAppointmentService.Filter;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A salon's appointment book (ADR-0013): walk-ins and phone bookings, confirmation, cancellation,
 * rescheduling and outcomes. Creating and rescheduling require an {@code Idempotency-Key} header.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/appointments")
class SalonAppointmentsController {

	private final SalonAppointmentService appointments;

	SalonAppointmentsController(SalonAppointmentService appointments) {
		this.appointments = appointments;
	}

	/**
	 * Appointments the caller may view, filtered. {@code from}/{@code to}: appointments overlapping
	 * that range. {@code needsAttention}: flagged by an override; {@code awaitingOutcome}: started,
	 * confirmed, no outcome yet.
	 */
	@GetMapping
	Page<SalonAppointmentView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@RequestParam(required = false) UUID branchId, @RequestParam(required = false) UUID staffId,
			@RequestParam(required = false) UUID clientId, @RequestParam(required = false) AppointmentStatus status,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
			@RequestParam(required = false) Boolean needsAttention,
			@RequestParam(required = false) Boolean awaitingOutcome,
			@PageableDefault(size = 50, sort = "startAt", direction = Sort.Direction.ASC) Pageable pageable) {
		return appointments.list(salonId, userId,
				new Filter(branchId, staffId, clientId, status, from, to, needsAttention, awaitingOutcome), pageable);
	}

	/** Walk-in or phone booking for a client of the salon; always {@code CONFIRMED}. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SalonAppointmentView book(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@RequestHeader(name = MyAppointmentsController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@Valid @RequestBody SalonBookRequest request) {
		return appointments.book(salonId, userId, idempotencyKey, new BookAtSalon(request.branchId(),
				request.clientId(), request.serviceIds(), request.staffId(), request.start(),
				Texts.blankToNull(request.internalNote())));
	}

	@GetMapping("/{appointmentId}")
	SalonAppointmentDetailView get(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId) {
		return appointments.get(salonId, appointmentId, userId);
	}

	@PostMapping("/{appointmentId}/confirm")
	SalonAppointmentView confirm(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId) {
		return appointments.confirm(salonId, appointmentId, userId);
	}

	@PostMapping("/{appointmentId}/reject")
	SalonAppointmentView reject(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId, @Valid @RequestBody(required = false) RejectRequest request) {
		return appointments.reject(salonId, appointmentId, userId,
				request == null ? null : Texts.blankToNull(request.reason()));
	}

	/** The reason is required; the customer sees it. */
	@PostMapping("/{appointmentId}/cancel")
	SalonAppointmentView cancel(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId, @Valid @RequestBody SalonCancelRequest request) {
		return appointments.cancel(salonId, appointmentId, userId, request.reason().strip());
	}

	/** Moves the appointment (same services); omit {@code staffId} to keep the staff member. */
	@PostMapping("/{appointmentId}/reschedule")
	SalonAppointmentView reschedule(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId,
			@RequestHeader(name = MyAppointmentsController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@Valid @RequestBody RescheduleRequest request) {
		return appointments.reschedule(salonId, appointmentId, userId, idempotencyKey,
				new Reschedule(request.start(), request.staffId()));
	}

	@PostMapping("/{appointmentId}/complete")
	SalonAppointmentView complete(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId) {
		return appointments.complete(salonId, appointmentId, userId);
	}

	@PostMapping("/{appointmentId}/no-show")
	SalonAppointmentView noShow(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId) {
		return appointments.markNoShow(salonId, appointmentId, userId);
	}

	/** Clears the "needs attention" flag set by an override. */
	@PostMapping("/{appointmentId}/resolve-attention")
	SalonAppointmentView resolveAttention(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId) {
		return appointments.resolveAttention(salonId, appointmentId, userId);
	}

	@PutMapping("/{appointmentId}/internal-note")
	SalonAppointmentView internalNote(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PathVariable UUID appointmentId, @Valid @RequestBody InternalNoteRequest request) {
		return appointments.changeInternalNote(salonId, appointmentId, userId, Texts.blankToNull(request.note()));
	}

	record SalonBookRequest(@NotNull UUID branchId, @NotNull UUID clientId,
			@NotNull @Size(min = 1, max = 5) List<@NotNull UUID> serviceIds, UUID staffId, @NotNull Instant start,
			@Size(max = 1000) String internalNote) {
	}

	record RejectRequest(@Size(max = 500) String reason) {
	}

	record SalonCancelRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
	}

	record RescheduleRequest(@NotNull Instant start, UUID staffId) {
	}

	record InternalNoteRequest(@Size(max = 1000) String note) {
	}

}
