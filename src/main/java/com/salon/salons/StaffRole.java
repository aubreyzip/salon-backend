package com.salon.salons;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.salon.salons.SalonPermission.APPOINTMENT_MANAGE_ANY;
import static com.salon.salons.SalonPermission.APPOINTMENT_MANAGE_OWN;
import static com.salon.salons.SalonPermission.APPOINTMENT_VIEW_ANY;
import static com.salon.salons.SalonPermission.APPOINTMENT_VIEW_OWN;
import static com.salon.salons.SalonPermission.BILLING_MANAGE;
import static com.salon.salons.SalonPermission.CLIENT_VIEW;
import static com.salon.salons.SalonPermission.SCHEDULE_MANAGE_OWN;

/**
 * A staff member's role within one salon (ADR-0010):
 * <ul>
 * <li>permissions: what the role may do;</li>
 * <li>branch scope: where branch-specific work is allowed (all branches, or assigned ones);</li>
 * <li>rank: who may manage whom. Only owners may manage members of equal or higher rank.</li>
 * </ul>
 * Branch assignments never add permissions; they only limit where branch-scoped roles work.
 */
public enum StaffRole {

	OWNER(4, PermissionScope.SALON, EnumSet.allOf(SalonPermission.class)),

	MANAGER(3, PermissionScope.SALON, EnumSet.complementOf(EnumSet.of(BILLING_MANAGE))),

	RECEPTIONIST(2, PermissionScope.ASSIGNED_BRANCHES, EnumSet.of(APPOINTMENT_VIEW_ANY, APPOINTMENT_MANAGE_ANY,
			CLIENT_VIEW)),

	STAFF(1, PermissionScope.ASSIGNED_BRANCHES, EnumSet.of(SCHEDULE_MANAGE_OWN, APPOINTMENT_VIEW_OWN,
			APPOINTMENT_MANAGE_OWN));

	private final int rank;

	private final PermissionScope branchScope;

	private final Set<SalonPermission> permissions;

	StaffRole(int rank, PermissionScope branchScope, EnumSet<SalonPermission> permissions) {
		this.rank = rank;
		this.branchScope = branchScope;
		this.permissions = Collections.unmodifiableSet(permissions);
	}

	public Set<SalonPermission> permissions() {
		return permissions;
	}

	public boolean has(SalonPermission permission) {
		return permissions.contains(permission);
	}

	public PermissionScope branchScope() {
		return branchScope;
	}

	public boolean outranks(StaffRole other) {
		return rank > other.rank;
	}

}
