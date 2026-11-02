package com.salon.salons;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated user's active membership in a salon, as resolved by {@link TenantAccess} for
 * the current request (never cached in tokens, so role and assignment changes apply immediately).
 *
 * @param staffId the user's staff member id in this salon, used for "own" resource checks
 * @param branchIds the branches the member is assigned to
 */
public record SalonMembership(UUID salonId, UUID staffId, UUID userId, StaffRole role, Set<UUID> branchIds) {

	public SalonMembership {
		branchIds = Set.copyOf(branchIds);
	}

	public boolean has(SalonPermission permission) {
		return role.has(permission);
	}

	/** Whether branch-specific work in this branch is within the member's scope. */
	public boolean coversBranch(UUID branchId) {
		return role.branchScope() == PermissionScope.SALON || branchIds.contains(branchId);
	}

	public boolean isOwnStaffResource(UUID resourceStaffId) {
		return staffId.equals(resourceStaffId);
	}

	/**
	 * For list queries: the branches results must be limited to, or empty when the member sees all
	 * branches. Use it to filter, e.g., appointment lists for receptionists.
	 */
	public Optional<Set<UUID>> branchRestriction() {
		return role.branchScope() == PermissionScope.SALON ? Optional.empty() : Optional.of(branchIds);
	}

}
