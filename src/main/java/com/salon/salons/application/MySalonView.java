package com.salon.salons.application;

import java.util.Set;
import java.util.UUID;

import com.salon.salons.SalonPermission;
import com.salon.salons.StaffRole;
import com.salon.salons.domain.SalonStatus;

/**
 * A salon the signed-in user is a member of, with their role and resulting permissions so clients
 * can show or hide management features.
 */
public record MySalonView(UUID salonId, String name, String slug, SalonStatus status, UUID staffId, StaffRole role,
		Set<SalonPermission> permissions) {
}
