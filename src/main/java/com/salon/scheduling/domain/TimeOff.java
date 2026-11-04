package com.salon.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A staff member is away from {@code startAt} to {@code endAt} (full or partial days, all
 * branches). Takes effect immediately; there is no approval workflow (ADR-0012).
 */
@Entity
@Table(name = "staff_time_off")
public class TimeOff extends BaseEntity {

	static final Duration MAX_LENGTH = Duration.ofDays(366);

	private UUID salonId;

	private UUID staffId;

	private Instant startAt;

	private Instant endAt;

	private String reason;

	private UUID createdByUserId;

	private Instant createdAt;

	protected TimeOff() {
	}

	public TimeOff(UUID salonId, UUID staffId, Instant startAt, Instant endAt, String reason, UUID createdByUserId,
			Instant now) {
		if (!startAt.isBefore(endAt)) {
			throw SchedulingErrors.invalidTimeRange("The start must be before the end.");
		}
		if (Duration.between(startAt, endAt).compareTo(MAX_LENGTH) > 0) {
			throw SchedulingErrors.invalidTimeRange("Time off can be at most 366 days long.");
		}
		this.salonId = salonId;
		this.staffId = staffId;
		this.startAt = startAt;
		this.endAt = endAt;
		this.reason = reason;
		this.createdByUserId = createdByUserId;
		this.createdAt = now;
	}

	public InstantRange range() {
		return new InstantRange(startAt, endAt);
	}

	public UUID getStaffId() {
		return staffId;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public String getReason() {
		return reason;
	}

}
