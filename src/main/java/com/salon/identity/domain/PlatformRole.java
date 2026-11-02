package com.salon.identity.domain;

/**
 * Platform-wide roles. Salon-scoped roles (owner, manager, staff) are tenant memberships and live
 * in the salon module, not here.
 */
public enum PlatformRole {

	PLATFORM_ADMIN, PLATFORM_SUPPORT

}
