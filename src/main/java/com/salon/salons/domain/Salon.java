package com.salon.salons.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * The tenant: a salon business with one or more branches (ADR-0008).
 */
@Entity
@Table(name = "salon")
public class Salon extends BaseEntity {

	private String name;

	private String slug;

	private String description;

	@Enumerated(EnumType.STRING)
	private SalonStatus status;

	private String reviewNote;

	private Instant submittedAt;

	private Instant approvedAt;

	private UUID createdByUserId;

	private Instant createdAt;

	private Instant updatedAt;

	protected Salon() {
	}

	public Salon(String name, String slug, String description, UUID createdByUserId, Instant now) {
		this.name = name;
		this.slug = slug;
		this.description = description;
		this.createdByUserId = createdByUserId;
		this.status = SalonStatus.DRAFT;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Suspended salons are frozen until a platform admin reactivates them. */
	public void ensureModifiable() {
		if (status == SalonStatus.SUSPENDED) {
			throw SalonErrors.salonSuspended();
		}
	}

	public void update(String name, String description, Instant now) {
		ensureModifiable();
		this.name = name;
		this.description = description;
		this.updatedAt = now;
	}

	public void submitForReview(Instant now) {
		requireStatus(SalonStatus.DRAFT, "submit");
		status = SalonStatus.PENDING_REVIEW;
		submittedAt = now;
		reviewNote = null;
		updatedAt = now;
	}

	public void approve(Instant now) {
		requireStatus(SalonStatus.PENDING_REVIEW, "approve");
		status = SalonStatus.ACTIVE;
		approvedAt = now;
		reviewNote = null;
		updatedAt = now;
	}

	public void reject(String note, Instant now) {
		requireStatus(SalonStatus.PENDING_REVIEW, "reject");
		status = SalonStatus.DRAFT;
		reviewNote = note;
		updatedAt = now;
	}

	public void suspend(String note, Instant now) {
		requireStatus(SalonStatus.ACTIVE, "suspend");
		status = SalonStatus.SUSPENDED;
		reviewNote = note;
		updatedAt = now;
	}

	public void reactivate(Instant now) {
		requireStatus(SalonStatus.SUSPENDED, "reactivate");
		status = SalonStatus.ACTIVE;
		reviewNote = null;
		updatedAt = now;
	}

	private void requireStatus(SalonStatus expected, String action) {
		if (status != expected) {
			throw SalonErrors.invalidStatusTransition(status, action);
		}
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public String getDescription() {
		return description;
	}

	public SalonStatus getStatus() {
		return status;
	}

	public String getReviewNote() {
		return reviewNote;
	}

	public Instant getSubmittedAt() {
		return submittedAt;
	}

	public Instant getApprovedAt() {
		return approvedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
