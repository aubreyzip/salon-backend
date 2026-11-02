package com.salon.salons.domain;

/**
 * Salon lifecycle: {@code DRAFT} (being set up) → {@code PENDING_REVIEW} (submitted) →
 * {@code ACTIVE} (approved by a platform admin, publicly listed) ⇄ {@code SUSPENDED}.
 * A rejected review returns the salon to {@code DRAFT}.
 */
public enum SalonStatus {

	DRAFT, PENDING_REVIEW, ACTIVE, SUSPENDED

}
