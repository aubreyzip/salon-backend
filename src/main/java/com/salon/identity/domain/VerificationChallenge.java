package com.salon.identity.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import com.salon.shared.SecureTokens;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;

/**
 * A one-time code sent to an email address or phone number. Only a hash of the code is stored.
 */
@Entity
@Table(name = "verification_challenge")
public class VerificationChallenge extends BaseEntity {

	private UUID userId;

	@Enumerated(EnumType.STRING)
	private ChallengePurpose purpose;

	@Enumerated(EnumType.STRING)
	private ChallengeChannel channel;

	private String target;

	private String codeHash;

	private int attempts;

	private Instant expiresAt;

	private Instant consumedAt;

	private Instant createdAt;

	protected VerificationChallenge() {
	}

	public VerificationChallenge(UUID userId, ChallengePurpose purpose, ChallengeChannel channel, String target,
			String code, Instant now, Instant expiresAt) {
		this.userId = userId;
		this.purpose = purpose;
		this.channel = channel;
		this.target = target;
		this.codeHash = SecureTokens.hashOtp(getId(), code);
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	public boolean isUsable(Instant now, int maxAttempts) {
		return consumedAt == null && expiresAt.isAfter(now) && attempts < maxAttempts;
	}

	/**
	 * Checks the code and counts the attempt. A matching code consumes the challenge.
	 */
	public boolean attempt(String code, Instant now) {
		attempts++;
		boolean matches = SecureTokens.constantTimeEquals(codeHash, SecureTokens.hashOtp(getId(), code));
		if (matches) {
			consumedAt = now;
		}
		return matches;
	}

	public void invalidate(Instant now) {
		if (consumedAt == null) {
			consumedAt = now;
		}
	}

	public UUID getUserId() {
		return userId;
	}

	public String getTarget() {
		return target;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
