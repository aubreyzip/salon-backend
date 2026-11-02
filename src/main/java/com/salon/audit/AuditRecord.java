package com.salon.audit;

import java.util.UUID;

/**
 * A security- or business-relevant action to append to the audit log.
 *
 * @param actorUserId the user who performed the action, or {@code null} for anonymous/system actions
 * @param salonId the tenant the action belongs to, or {@code null} for platform-level actions
 * @param action a stable action name, e.g. {@code identity.login.failed}
 * @param entityType the affected entity type, if any
 * @param entityId the affected entity id, if any
 * @param details short free text without personal data, secrets or tokens
 */
public record AuditRecord(UUID actorUserId, UUID salonId, String action, String entityType, String entityId,
		String details) {

	public static AuditRecord of(UUID actorUserId, String action, String entityType, Object entityId) {
		return new AuditRecord(actorUserId, null, action, entityType, entityId == null ? null : entityId.toString(),
				null);
	}

}
