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
 * Opening hours for one date that replace the weekly hours, e.g. a shorter day before a holiday.
 * No intervals means the branch is closed that day.
 */
@Entity
@Table(name = "branch_special_day")
public class SpecialDay extends BaseEntity {

	private UUID salonId;

	private UUID branchId;

	private LocalDate onDate;

	private String note;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "branch_special_day_interval", joinColumns = @JoinColumn(name = "special_day_id"))
	private List<TimeInterval> intervals = new ArrayList<>();

	private Instant updatedAt;

	protected SpecialDay() {
	}

	public SpecialDay(UUID salonId, UUID branchId, LocalDate onDate) {
		this.salonId = salonId;
		this.branchId = branchId;
		this.onDate = onDate;
	}

	/**
	 * @throws com.salon.shared.ApiException {@code OVERLAPPING_INTERVALS} if intervals overlap
	 */
	public void change(List<TimeInterval> newIntervals, String note, Instant now) {
		List<TimeInterval> sorted = TimeInterval.sortedNonOverlapping(newIntervals);
		intervals.clear();
		intervals.addAll(sorted);
		this.note = note;
		this.updatedAt = now;
	}

	public boolean isClosed() {
		return intervals.isEmpty();
	}

	public UUID getBranchId() {
		return branchId;
	}

	public LocalDate getOnDate() {
		return onDate;
	}

	public String getNote() {
		return note;
	}

	public List<TimeInterval> getIntervals() {
		return intervals.stream().sorted(TimeInterval.BY_START).toList();
	}

}
