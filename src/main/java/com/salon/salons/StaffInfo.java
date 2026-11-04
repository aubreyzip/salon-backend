package com.salon.salons;

import java.util.Set;
import java.util.UUID;

/**
 * Read-only staff facts other modules need for scheduling and booking.
 *
 * @param branchIds the branches the staff member is assigned to
 */
public record StaffInfo(UUID id, UUID salonId, String displayName, boolean active, boolean bookable,
		Set<UUID> branchIds) {

	public StaffInfo {
		branchIds = Set.copyOf(branchIds);
	}

	/** Whether customers can book this person at the branch. */
	public boolean isBookableAt(UUID branchId) {
		return active && bookable && branchIds.contains(branchId);
	}

}
