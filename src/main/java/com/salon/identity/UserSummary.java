package com.salon.identity;

import java.util.UUID;

/**
 * Minimal, read-only view of a user account for other modules.
 */
public record UserSummary(UUID id, String email, boolean emailVerified, String displayName, boolean active) {
}
