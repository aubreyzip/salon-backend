package com.salon.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A staff member can perform a service. Skills are explicit: without one, the staff member is not
 * offered for the service. The optional duration override applies in every branch and wins over
 * the branch and service durations (ADR-0012).
 */
@Entity
@Table(name = "staff_skill")
public class StaffSkill extends BaseEntity {

	private UUID salonId;

	private UUID staffId;

	private UUID serviceId;

	private Integer durationOverrideMinutes;

	private Instant createdAt;

	protected StaffSkill() {
	}

	public StaffSkill(UUID salonId, UUID staffId, UUID serviceId, Integer durationOverrideMinutes, Instant now) {
		this.salonId = salonId;
		this.staffId = staffId;
		this.serviceId = serviceId;
		this.durationOverrideMinutes = durationOverrideMinutes;
		this.createdAt = now;
	}

	public UUID getSalonId() {
		return salonId;
	}

	public UUID getStaffId() {
		return staffId;
	}

	public UUID getServiceId() {
		return serviceId;
	}

	public Integer getDurationOverrideMinutes() {
		return durationOverrideMinutes;
	}

}
