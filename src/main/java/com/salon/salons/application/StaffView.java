package com.salon.salons.application;

import java.util.Set;
import java.util.UUID;

import com.salon.salons.StaffRole;

/**
 * @param linked whether a user account is linked to this staff member
 * @param invitationPending whether an unexpired invitation is waiting to be accepted
 */
public record StaffView(UUID id, String displayName, String title, String bio, StaffRole role, boolean active,
		boolean bookable, Set<UUID> branchIds, boolean linked, boolean invitationPending) {
}
