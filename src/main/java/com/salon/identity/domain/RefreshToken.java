package com.salon.identity.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A refresh token, stored only as a hash. Tokens issued from one login form a family; presenting a
 * token that was already rotated means it was stolen or replayed, and the whole family is revoked.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends BaseEntity {

	private UUID userId;

	private UUID familyId;

	private String tokenHash;

	private Instant issuedAt;

	private Instant expiresAt;

	private Instant revokedAt;

	@Enumerated(EnumType.STRING)
	private RevokeReason revokeReason;

	protected RefreshToken() {
	}

	public RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt) {
		this.userId = userId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

	public void revoke(RevokeReason reason, Instant now) {
		if (revokedAt == null) {
			revokedAt = now;
			revokeReason = reason;
		}
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public RevokeReason getRevokeReason() {
		return revokeReason;
	}

}
