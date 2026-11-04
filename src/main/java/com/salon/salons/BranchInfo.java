package com.salon.salons;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Read-only branch facts other modules need for scheduling and booking.
 *
 * @param active the branch is active (inactive branches can be configured but are not bookable)
 * @param salonActive the salon is {@code ACTIVE}, i.e. approved and not suspended
 * @param slotIntervalMinutes grid on which appointment start times are offered
 * @param minNoticeMinutes how far in advance a customer booking must be made
 * @param maxAdvanceDays how far into the future bookings are accepted
 */
public record BranchInfo(UUID id, UUID salonId, String name, ZoneId timeZone, boolean active, boolean salonActive,
		int slotIntervalMinutes, int minNoticeMinutes, int maxAdvanceDays) {

	/** Whether customers may see and book this branch. */
	public boolean isPublic() {
		return active && salonActive;
	}

}
