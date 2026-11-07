package com.salon.booking.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A used {@code Idempotency-Key} (ADR-0005, ADR-0013 decision 41): per user, with the endpoint
 * ({@code scope}) and a hash of the request, and the appointment it produced. Kept 24 hours.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord extends BaseEntity {

	private UUID userId;

	@Column(name = "idempotency_key")
	private String key;

	private String scope;

	private String requestHash;

	private UUID appointmentId;

	private Integer responseStatus;

	private Instant createdAt;

	protected IdempotencyRecord() {
	}

	public IdempotencyRecord(UUID userId, String key, String scope, String requestHash, Instant now) {
		this.userId = userId;
		this.key = key;
		this.scope = scope;
		this.requestHash = requestHash;
		this.createdAt = now;
	}

	/** Whether a retry with this key is the same request (same endpoint, same body). */
	public boolean matches(String otherScope, String otherRequestHash) {
		return scope.equals(otherScope) && requestHash.equals(otherRequestHash);
	}

	public void complete(UUID resultAppointmentId, int status) {
		this.appointmentId = resultAppointmentId;
		this.responseStatus = status;
	}

	public UUID getAppointmentId() {
		return appointmentId;
	}

}
