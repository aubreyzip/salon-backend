package com.salon.salons.domain;

import java.util.UUID;

import jakarta.persistence.Embeddable;

/**
 * A row of {@code staff_branch}. Carries {@code salonId} so the composite foreign keys can
 * guarantee that staff and branch belong to the same salon.
 */
@Embeddable
public record BranchAssignment(UUID salonId, UUID branchId) {
}
