package com.salon.salons;

/**
 * Salon-scoped permissions. Roles map to permission sets in {@link StaffRole}; code checks
 * permissions, never role names, so roles can be made configurable later.
 */
public enum SalonPermission {

	SALON_MANAGE, BRANCH_MANAGE, STAFF_MANAGE, CATALOG_MANAGE, SCHEDULE_MANAGE_ANY, SCHEDULE_MANAGE_OWN,
	APPOINTMENT_VIEW_ANY, APPOINTMENT_VIEW_OWN, APPOINTMENT_MANAGE_ANY, APPOINTMENT_MANAGE_OWN, CLIENT_VIEW,
	BILLING_VIEW, BILLING_MANAGE,

	/** Emergency override of changes that conflict with appointments (ADR-0012 decision 25). */
	APPOINTMENT_CONFLICT_OVERRIDE

}
