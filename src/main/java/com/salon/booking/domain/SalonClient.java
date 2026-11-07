package com.salon.booking.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A salon's client (ADR-0013 decision 35). Either linked to a customer account (created on the
 * customer's first booking at the salon; name and phone come from the account, so they stay
 * current) or a walk-in / phone-booking record with its own name and optional phone.
 */
@Entity
@Table(name = "salon_client")
public class SalonClient extends BaseEntity {

	private UUID salonId;

	private UUID userId;

	private String displayName;

	private String phoneE164;

	private UUID createdByUserId;

	private Instant createdAt;

	protected SalonClient() {
	}

	private SalonClient(UUID salonId, UUID userId, String displayName, String phoneE164, UUID createdByUserId,
			Instant now) {
		this.salonId = salonId;
		this.userId = userId;
		this.displayName = displayName;
		this.phoneE164 = phoneE164;
		this.createdByUserId = createdByUserId;
		this.createdAt = now;
	}

	public static SalonClient linked(UUID salonId, UUID userId, Instant now) {
		return new SalonClient(salonId, userId, null, null, userId, now);
	}

	/**
	 * @param phoneE164 normalized phone, or {@code null}
	 */
	public static SalonClient walkIn(UUID salonId, String displayName, String phoneE164, UUID createdByUserId,
			Instant now) {
		return new SalonClient(salonId, null, displayName, phoneE164, createdByUserId, now);
	}

	public boolean isLinked() {
		return userId != null;
	}

	public UUID getSalonId() {
		return salonId;
	}

	/** The linked customer account, or {@code null} for walk-in records. */
	public UUID getUserId() {
		return userId;
	}

	/** Walk-in records only; linked clients use the account's name. */
	public String getDisplayName() {
		return displayName;
	}

	public String getPhoneE164() {
		return phoneE164;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
