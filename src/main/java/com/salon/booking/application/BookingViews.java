package com.salon.booking.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.booking.domain.AppointmentHistoryEntry;
import com.salon.booking.domain.AppointmentItem;
import com.salon.booking.domain.AppointmentSource;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.booking.domain.CancelledBy;
import com.salon.booking.domain.HistoryAction;

/**
 * Response bodies of the booking API. Customer views never contain internal salon data (internal
 * note, attention flag, other clients); salon views contain client contact data only for
 * appointments the caller may view (ADR-0013 decision 36).
 */
public final class BookingViews {

	private BookingViews() {
	}

	/**
	 * @param durationMinutes as booked with the assigned staff member
	 */
	public record ItemView(UUID serviceId, String name, int durationMinutes, BigDecimal price, String currency,
			boolean priceFrom) {

		static ItemView of(AppointmentItem item) {
			return new ItemView(item.serviceId(), item.serviceName(), item.durationMinutes(), item.price(),
					item.currency(), item.priceFrom());
		}

	}

	public record BranchSummary(UUID id, String name, String timeZone) {
	}

	public record StaffSummary(UUID id, String displayName) {
	}

	/**
	 * An appointment as the customer sees it.
	 *
	 * @param statusReason the salon's reason for a cancellation or rejection, or the customer's own
	 * @param confirmationDeadline for {@code PENDING} appointments: when they expire unless confirmed
	 * @param changeableUntil the customer may cancel or reschedule online until this moment
	 */
	public record CustomerAppointmentView(UUID id, UUID salonId, BranchSummary branch, StaffSummary staff,
			AppointmentStatus status, Instant start, Instant end, List<ItemView> items, BigDecimal totalPrice,
			String currency, String customerNote, String statusReason, CancelledBy cancelledBy,
			Instant confirmationDeadline, Instant changeableUntil, Instant createdAt) {
	}

	/**
	 * A salon's client.
	 *
	 * @param linked the client booked online with an account; name and phone come from the account
	 * @param phone E.164, or {@code null}
	 */
	public record ClientView(UUID id, String displayName, String phone, boolean linked, Instant createdAt) {
	}

	/**
	 * @param byUserId who used the override
	 */
	public record AttentionView(String reason, Instant at, UUID byUserId) {
	}

	/**
	 * An appointment as salon staff see it.
	 *
	 * @param blockEnd when the staff member is free again (end plus cleanup buffer)
	 * @param attention set if an emergency override flagged the appointment (decision 38)
	 * @param awaitingOutcome confirmed, started, and no outcome recorded yet (decision 33)
	 */
	public record SalonAppointmentView(UUID id, UUID branchId, UUID staffId, ClientView client,
			AppointmentStatus status, AppointmentSource source, Instant start, Instant end, Instant blockEnd,
			List<ItemView> items, BigDecimal totalPrice, String currency, String customerNote, String internalNote,
			String statusReason, CancelledBy cancelledBy, Instant confirmationDeadline, AttentionView attention,
			boolean awaitingOutcome, UUID createdByUserId, Instant createdAt) {
	}

	/**
	 * @param actorUserId who made the change; {@code null} for the system (e.g. expiry)
	 */
	public record HistoryView(HistoryAction action, AppointmentStatus fromStatus, AppointmentStatus toStatus,
			UUID actorUserId, String reason, Instant start, UUID staffId, Instant at) {

		static HistoryView of(AppointmentHistoryEntry entry) {
			return new HistoryView(entry.getAction(), entry.getFromStatus(), entry.getToStatus(), entry.getActorUserId(),
					entry.getReason(), entry.getStartAt(), entry.getStaffId(), entry.getOccurredAt());
		}

	}

	public record SalonAppointmentDetailView(SalonAppointmentView appointment, List<HistoryView> history) {
	}

}
