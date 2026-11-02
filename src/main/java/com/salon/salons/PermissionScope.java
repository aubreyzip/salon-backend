package com.salon.salons;

/**
 * Where a role's permissions apply for branch-specific work (ADR-0010).
 */
public enum PermissionScope {

	/** Every branch of the salon, regardless of branch assignments. */
	SALON,

	/** Only the branches the staff member is assigned to. No assignments means no branch access. */
	ASSIGNED_BRANCHES

}
