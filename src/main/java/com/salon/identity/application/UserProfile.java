package com.salon.identity.application;

import java.util.Set;
import java.util.UUID;

public record UserProfile(UUID id, String email, boolean emailVerified, String phone, boolean phoneVerified,
		String displayName, Set<String> platformRoles) {
}
