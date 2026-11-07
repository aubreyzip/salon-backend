package com.salon.booking.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One entry of an appointment's history (ADR-0009): every status change, reschedule and attention
 * flag, with who did it ({@code null} for the system, e.g. expiry).
 */
@Entity
@Table(name = "appointment_history")
public class AppointmentHistoryEntry extends BaseEntity {

	private UUID appointmentId;

	private UUID salonId;

	@Enumerated(EnumType.STRING)
	private HistoryAction action;

	@Enumerated(EnumType.STRING)
	private AppointmentStatus fromStatus;

	@Enumerated(EnumType.STRING)
	private AppointmentStatus toStatus;

	private UUID actorUserId;

	private String reason;

	private Instant startAt;

	private UUID staffId;

	private Instant occurredAt;

	protected AppointmentHistoryEntry() {
	}

	/**
	 * @param fromStatus the status before the change, or {@code null} for the booking itself
	 * @param reason a reason given with the change, if any
	 */
	public AppointmentHistoryEntry(Appointment appointment, HistoryAction action, AppointmentStatus fromStatus,
			UUID actorUserId, String reason, Instant now) {
		this.appointmentId = appointment.getId();
		this.salonId = appointment.getSalonId();
		this.action = action;
		this.fromStatus = fromStatus;
		this.toStatus = appointment.getStatus();
		this.actorUserId = actorUserId;
		this.reason = reason;
		this.startAt = appointment.getStartAt();
		this.staffId = appointment.getStaffId();
		this.occurredAt = now;
	}

	public HistoryAction getAction() {
		return action;
	}

	public AppointmentStatus getFromStatus() {
		return fromStatus;
	}

	public AppointmentStatus getToStatus() {
		return toStatus;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public String getReason() {
		return reason;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public UUID getStaffId() {
		return staffId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

}
