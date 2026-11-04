package com.salon.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Whole days a branch is closed (renovation, holidays), inclusive on both ends.
 */
@Entity
@Table(name = "branch_closure")
public class BranchClosure extends BaseEntity {

	private UUID salonId;

	private UUID branchId;

	private LocalDate startDate;

	private LocalDate endDate;

	private String reason;

	private Instant createdAt;

	protected BranchClosure() {
	}

	public BranchClosure(UUID salonId, UUID branchId, DateRange dates, String reason, Instant now) {
		if (dates.end() == null) {
			throw SchedulingErrors.invalidDateRange("A closure needs an end date.");
		}
		this.salonId = salonId;
		this.branchId = branchId;
		this.startDate = dates.start();
		this.endDate = dates.end();
		this.reason = reason;
		this.createdAt = now;
	}

	public DateRange dates() {
		return new DateRange(startDate, endDate);
	}

	public UUID getBranchId() {
		return branchId;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public String getReason() {
		return reason;
	}

}
