package com.salon.audit.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Immutable audit log row. Implements {@link Persistable} so that saving always inserts.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry implements Persistable<UUID> {

	@Id
	private UUID id;

	private Instant occurredAt;

	private UUID actorUserId;

	private UUID salonId;

	private String action;

	private String entityType;

	private String entityId;

	private String details;

	private String clientIp;

	private String userAgent;

	@Transient
	private boolean persisted;

	protected AuditLogEntry() {
		this.persisted = true;
	}

	public AuditLogEntry(Instant occurredAt, UUID actorUserId, UUID salonId, String action, String entityType,
			String entityId, String details, String clientIp, String userAgent) {
		this.id = Ids.newId();
		this.occurredAt = occurredAt;
		this.actorUserId = actorUserId;
		this.salonId = salonId;
		this.action = action;
		this.entityType = entityType;
		this.entityId = entityId;
		this.details = details;
		this.clientIp = clientIp;
		this.userAgent = userAgent;
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return !persisted;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public String getAction() {
		return action;
	}

	public String getEntityId() {
		return entityId;
	}

}
