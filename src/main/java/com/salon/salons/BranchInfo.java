package com.salon.salons;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Read-only branch facts other modules need for scheduling and booking.
 *
 * @param active the branch is active (inactive branches can be configured, and booked by the salon,
 * but are not public)
 * @param salonActive the salon is {@code ACTIVE}, i.e. approved and not suspended
 * @param slotIntervalMinutes grid on which appointment start times are offered
 * @param minNoticeMinutes how far in advance a customer booking must be made
 * @param maxAdvanceDays how far into the future customer bookings are accepted
 * @param autoConfirm customer bookings are confirmed immediately; otherwise they wait (ADR-0009)
 * @param cancellationCutoffMinutes customers may cancel or reschedule until this long before the start
 * @param pendingConfirmationTimeoutMinutes unconfirmed bookings expire after this time
 */
public record BranchInfo(UUID id, UUID salonId, String name, ZoneId timeZone, boolean active, boolean salonActive,
		int slotIntervalMinutes, int minNoticeMinutes, int maxAdvanceDays, boolean autoConfirm,
		int cancellationCutoffMinutes, int pendingConfirmationTimeoutMinutes) {

	/** Whether customers may see and book this branch. */
	public boolean isPublic() {
		return active && salonActive;
	}

}
