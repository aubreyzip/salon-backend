package com.salon.salons.application;

import java.time.Instant;
import java.util.UUID;

import com.salon.salons.domain.Salon;
import com.salon.salons.domain.SalonStatus;

/**
 * @param reviewNote the admin's note after a rejection or suspension
 */
public record SalonView(UUID id, String name, String slug, String description, SalonStatus status,
		String reviewNote, Instant submittedAt, Instant approvedAt, Instant createdAt) {

	static SalonView of(Salon salon) {
		return new SalonView(salon.getId(), salon.getName(), salon.getSlug(), salon.getDescription(),
				salon.getStatus(), salon.getReviewNote(), salon.getSubmittedAt(), salon.getApprovedAt(),
				salon.getCreatedAt());
	}

}
