package com.salon.identity;

import java.util.UUID;

/**
 * Minimal, read-only view of a user account for other modules.
 *
 * @param phoneE164 the phone number, or {@code null}; verified only if {@code phoneVerified}
 * @param phoneVerified the phone was verified by SMS code (required before booking, ADR-0007)
 */
public record UserSummary(UUID id, String email, boolean emailVerified, String displayName, boolean active,
		String phoneE164, boolean phoneVerified) {
}
