package com.salon.salons.domain;

import java.util.Set;

import jakarta.persistence.Embeddable;

/**
 * Per-branch booking rules used by scheduling and booking (ADR-0009).
 *
 * @param autoConfirm bookings are confirmed immediately; otherwise they wait for the salon
 * @param slotIntervalMinutes grid on which appointment start times are offered
 * @param minNoticeMinutes how far in advance a booking must be made
 * @param maxAdvanceDays how far into the future bookings are accepted
 * @param cancellationCutoffMinutes customers may cancel or reschedule until this long before the start
 * @param pendingConfirmationTimeoutMinutes unconfirmed bookings expire after this time
 */
@Embeddable
public record BookingPolicy(boolean autoConfirm, int slotIntervalMinutes, int minNoticeMinutes, int maxAdvanceDays,
		int cancellationCutoffMinutes, int pendingConfirmationTimeoutMinutes) {

	public static final Set<Integer> SLOT_INTERVALS = Set.of(5, 10, 15, 20, 30, 60);

	public static BookingPolicy defaults() {
		return new BookingPolicy(true, 15, 60, 60, 120, 120);
	}

	/** Checks the rules that are also enforced by database constraints, with a readable error. */
	public BookingPolicy validated() {
		if (!SLOT_INTERVALS.contains(slotIntervalMinutes)) {
			throw SalonErrors.invalidBookingPolicy("slotIntervalMinutes must be one of 5, 10, 15, 20, 30 or 60.");
		}
		if (minNoticeMinutes < 0 || minNoticeMinutes > 10_080) {
			throw SalonErrors.invalidBookingPolicy("minNoticeMinutes must be between 0 and 10080.");
		}
		if (maxAdvanceDays < 1 || maxAdvanceDays > 365) {
			throw SalonErrors.invalidBookingPolicy("maxAdvanceDays must be between 1 and 365.");
		}
		if (cancellationCutoffMinutes < 0 || cancellationCutoffMinutes > 10_080) {
			throw SalonErrors.invalidBookingPolicy("cancellationCutoffMinutes must be between 0 and 10080.");
		}
		if (pendingConfirmationTimeoutMinutes < 15 || pendingConfirmationTimeoutMinutes > 2_880) {
			throw SalonErrors.invalidBookingPolicy("pendingConfirmationTimeoutMinutes must be between 15 and 2880.");
		}
		return this;
	}

}
