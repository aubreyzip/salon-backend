package com.salon.salons;

import java.util.UUID;

import com.salon.salons.domain.SalonErrors;
import com.salon.salons.domain.StaffMemberRepository;
import com.salon.salons.domain.StaffStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single gate for salon-scoped (tenant) operations (ADR-0002, ADR-0010). Every use case on
 * {@code /api/v1/salons/{salonId}/...} calls one of these methods before touching tenant data.
 * <ul>
 * <li>{@link #require}: salon-level work (settings, branches, staff, catalog). Permission only.</li>
 * <li>{@link #requireInBranch}: branch-specific operational work (a branch's appointment book,
 * schedules, clients). Permission plus the role's branch scope.</li>
 * <li>{@link #requireForStaffResource}: work on one staff member's resource in a branch (their
 * schedule, their appointments). The "any" permission, or the "own" permission for the caller's
 * own resources, plus branch scope.</li>
 * </ul>
 * Errors: non-members get {@code 404 SALON_NOT_FOUND} (salon ids cannot be probed), missing
 * permissions {@code 403 INSUFFICIENT_PERMISSION}, branches outside the member's scope
 * {@code 403 BRANCH_NOT_ASSIGNED}. Callers must still load resources by {@code (id, salonId)}.
 */
@Service
@Transactional(readOnly = true)
public class TenantAccess {

	private final StaffMemberRepository staff;

	TenantAccess(StaffMemberRepository staff) {
		this.staff = staff;
	}

	public SalonMembership requireMember(UUID salonId, UUID userId) {
		return staff.findBySalonIdAndUserIdAndStatus(salonId, userId, StaffStatus.ACTIVE)
			.map(member -> new SalonMembership(salonId, member.getId(), userId, member.getRole(),
					member.getBranchIds()))
			.orElseThrow(SalonErrors::salonNotFound);
	}

	public SalonMembership require(UUID salonId, UUID userId, SalonPermission permission) {
		SalonMembership membership = requireMember(salonId, userId);
		if (!membership.has(permission)) {
			throw SalonErrors.insufficientPermission();
		}
		return membership;
	}

	/** Salon-level work that any one of the permissions allows (e.g. creating a client to book). */
	public SalonMembership requireAny(UUID salonId, UUID userId, SalonPermission... permissions) {
		SalonMembership membership = requireMember(salonId, userId);
		for (SalonPermission permission : permissions) {
			if (membership.has(permission)) {
				return membership;
			}
		}
		throw SalonErrors.insufficientPermission();
	}

	public SalonMembership requireInBranch(UUID salonId, UUID branchId, UUID userId, SalonPermission permission) {
		SalonMembership membership = require(salonId, userId, permission);
		if (!membership.coversBranch(branchId)) {
			throw SalonErrors.branchNotAssigned();
		}
		return membership;
	}

	public SalonMembership requireForStaffResource(UUID salonId, UUID branchId, UUID resourceStaffId, UUID userId,
			SalonPermission anyPermission, SalonPermission ownPermission) {
		SalonMembership membership = requireMember(salonId, userId);
		boolean permitted = membership.has(anyPermission)
				|| (membership.has(ownPermission) && membership.isOwnStaffResource(resourceStaffId));
		if (!permitted) {
			throw SalonErrors.insufficientPermission();
		}
		if (!membership.coversBranch(branchId)) {
			throw SalonErrors.branchNotAssigned();
		}
		return membership;
	}

	/**
	 * Work on one staff member's resource that is not tied to a branch (e.g. their time off). The
	 * "any" permission, or the "own" permission for the caller's own resources. For branch-scoped
	 * members acting on someone else, the staff member must work in one of the member's branches.
	 * @throws com.salon.shared.ApiException {@code 404 STAFF_NOT_FOUND} if the staff member is not in
	 * this salon (checked after membership, so outsiders still get {@code SALON_NOT_FOUND})
	 */
	public SalonMembership requireForStaffMember(UUID salonId, UUID resourceStaffId, UUID userId,
			SalonPermission anyPermission, SalonPermission ownPermission) {
		SalonMembership membership = requireMember(salonId, userId);
		boolean own = membership.isOwnStaffResource(resourceStaffId);
		if (!membership.has(anyPermission) && !(own && membership.has(ownPermission))) {
			throw SalonErrors.insufficientPermission();
		}
		var target = staff.findByIdAndSalonId(resourceStaffId, salonId).orElseThrow(SalonErrors::staffNotFound);
		boolean sharesBranch = membership.branchRestriction()
			.map(branchIds -> target.getBranchIds().stream().anyMatch(branchIds::contains))
			.orElse(true);
		if (!own && !sharesBranch) {
			throw SalonErrors.branchNotAssigned();
		}
		return membership;
	}

}
