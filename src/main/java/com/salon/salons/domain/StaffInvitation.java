package com.salon.salons.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * An invitation for a person to link their account to a staff member. Only a hash of the token
 * is stored; the token is sent to the invited email address.
 */
@Entity
@Table(name = "staff_invitation")
public class StaffInvitation extends BaseEntity {

	private UUID salonId;

	private UUID staffId;

	private String email;

	private String tokenHash;

	private UUID invitedByUserId;

	private Instant expiresAt;

	private Instant acceptedAt;

	private UUID acceptedByUserId;

	private Instant revokedAt;

	private Instant createdAt;

	protected StaffInvitation() {
	}

	public StaffInvitation(UUID salonId, UUID staffId, String normalizedEmail, String tokenHash, UUID invitedByUserId,
			Instant now, Instant expiresAt) {
		this.salonId = salonId;
		this.staffId = staffId;
		this.email = normalizedEmail;
		this.tokenHash = tokenHash;
		this.invitedByUserId = invitedByUserId;
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	public boolean isOpen(Instant now) {
		return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
	}

	public void accept(UUID userId, Instant now) {
		this.acceptedAt = now;
		this.acceptedByUserId = userId;
	}

	public void revoke(Instant now) {
		if (acceptedAt == null && revokedAt == null) {
			this.revokedAt = now;
		}
	}

	public UUID getSalonId() {
		return salonId;
	}

	public UUID getStaffId() {
		return staffId;
	}

	public String getEmail() {
		return email;
	}

}
