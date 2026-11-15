package com.salon.notification.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A persistent in-app notification for one user (ADR-0014 decision 45). It holds the facts of the
 * change it announces, copied from the immutable source when it was created (decision 56); the
 * text is rendered from its template when read (decision 51). Only {@code readAt} ever changes.
 */
@Entity
@Table(name = "notification")
public class Notification extends BaseEntity {

	private UUID userId;

	@Enumerated(EnumType.STRING)
	private NotificationKind kind;

	private UUID sourceId;

	private UUID salonId;

	private UUID branchId;

	private UUID appointmentId;

	private Instant startAt;

	private Instant previousStartAt;

	private String reason;

	private Instant createdAt;

	private Instant readAt;

	protected Notification() {
	}

	private Notification(UUID userId, NotificationKind kind, UUID sourceId, Instant createdAt) {
		this.userId = userId;
		this.kind = kind;
		this.sourceId = sourceId;
		this.createdAt = createdAt;
	}

	/**
	 * @param historyEntryId the appointment history entry of the change, the idempotency source
	 * @param startAt the appointment's start after the change
	 * @param previousStartAt the start before a reschedule; kept only if the kind shows it
	 * @param reason the reason given with the change; kept only if the kind shows it
	 */
	public static Notification forAppointment(UUID userId, NotificationKind kind, UUID historyEntryId, UUID salonId,
			UUID branchId, UUID appointmentId, Instant startAt, Instant previousStartAt, String reason,
			Instant now) {
		Notification notification = new Notification(userId, kind, historyEntryId, now);
		notification.salonId = salonId;
		notification.branchId = branchId;
		notification.appointmentId = appointmentId;
		notification.startAt = startAt;
		notification.previousStartAt = kind.showsPreviousStart() ? previousStartAt : null;
		notification.reason = kind.showsReason() ? reason : null;
		return notification;
	}

	public UUID getUserId() {
		return userId;
	}

	public NotificationKind getKind() {
		return kind;
	}

	public UUID getSourceId() {
		return sourceId;
	}

	public UUID getSalonId() {
		return salonId;
	}

	public UUID getBranchId() {
		return branchId;
	}

	public UUID getAppointmentId() {
		return appointmentId;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getPreviousStartAt() {
		return previousStartAt;
	}

	public String getReason() {
		return reason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getReadAt() {
		return readAt;
	}

}
