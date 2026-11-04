package com.salon.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * A staff member's weekly working hours at one branch, valid from {@code effectiveFrom} to
 * {@code effectiveTo} (inclusive, {@code null} = open-ended). Several intervals per day are allowed;
 * no overnight intervals.
 */
@Entity
@Table(name = "staff_working_period")
public class WorkingPeriod extends BaseEntity {

	private UUID salonId;

	private UUID staffId;

	private UUID branchId;

	private LocalDate effectiveFrom;

	private LocalDate effectiveTo;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "staff_working_interval", joinColumns = @JoinColumn(name = "period_id"))
	private List<WeeklyInterval> intervals = new ArrayList<>();

	private Instant createdAt;

	protected WorkingPeriod() {
	}

	/**
	 * @throws com.salon.shared.ApiException {@code OVERLAPPING_INTERVALS} if intervals of a day overlap
	 */
	public WorkingPeriod(UUID salonId, UUID staffId, UUID branchId, DateRange dates, List<WeeklyInterval> intervals,
			Instant now) {
		WeeklyInterval.byDay(intervals);
		this.salonId = salonId;
		this.staffId = staffId;
		this.branchId = branchId;
		this.effectiveFrom = dates.start();
		this.effectiveTo = dates.end();
		this.intervals.addAll(intervals);
		this.createdAt = now;
	}

	public DateRange dates() {
		return new DateRange(effectiveFrom, effectiveTo);
	}

	public WorkingPattern pattern() {
		return WorkingPattern.of(dates(), intervals);
	}

	public UUID getStaffId() {
		return staffId;
	}

	public UUID getBranchId() {
		return branchId;
	}

	public LocalDate getEffectiveFrom() {
		return effectiveFrom;
	}

	public LocalDate getEffectiveTo() {
		return effectiveTo;
	}

	public List<WeeklyInterval> getIntervals() {
		return List.copyOf(intervals);
	}

}
