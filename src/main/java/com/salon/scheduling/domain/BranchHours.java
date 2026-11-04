package com.salon.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * The weekly opening hours of a branch, several intervals per day allowed (e.g. a lunch break).
 * A branch without opening hours is closed.
 */
@Entity
@Table(name = "branch_hours")
public class BranchHours extends BaseEntity {

	private UUID salonId;

	private UUID branchId;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "branch_hours_interval", joinColumns = @JoinColumn(name = "branch_hours_id"))
	private List<WeeklyInterval> intervals = new ArrayList<>();

	private Instant updatedAt;

	protected BranchHours() {
	}

	public BranchHours(UUID salonId, UUID branchId, Instant now) {
		this.salonId = salonId;
		this.branchId = branchId;
		this.updatedAt = now;
	}

	/**
	 * @throws com.salon.shared.ApiException {@code OVERLAPPING_INTERVALS} if intervals of a day overlap
	 */
	public void replace(List<WeeklyInterval> newIntervals, Instant now) {
		WeeklyInterval.byDay(newIntervals);
		intervals.clear();
		intervals.addAll(newIntervals);
		updatedAt = now;
	}

	public Map<DayOfWeek, List<TimeInterval>> byDay() {
		return WeeklyInterval.byDay(intervals);
	}

	public UUID getSalonId() {
		return salonId;
	}

	public UUID getBranchId() {
		return branchId;
	}

	public List<WeeklyInterval> getIntervals() {
		return List.copyOf(intervals);
	}

}
