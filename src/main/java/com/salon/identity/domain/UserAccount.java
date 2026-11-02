package com.salon.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import com.salon.shared.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_account")
public class UserAccount extends BaseEntity {

	private String email;

	private Instant emailVerifiedAt;

	@Column(name = "phone_e164")
	private String phoneE164;

	private Instant phoneVerifiedAt;

	private String passwordHash;

	private String displayName;

	@Enumerated(EnumType.STRING)
	private UserStatus status;

	private int failedLoginAttempts;

	private Instant lockedUntil;

	private Instant createdAt;

	private Instant updatedAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "user_platform_role", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "role")
	@Enumerated(EnumType.STRING)
	private Set<PlatformRole> platformRoles = EnumSet.noneOf(PlatformRole.class);

	protected UserAccount() {
	}

	public UserAccount(String normalizedEmail, String passwordHash, String displayName, Instant now) {
		this.email = normalizedEmail;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
		this.status = UserStatus.ACTIVE;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public boolean isEmailVerified() {
		return emailVerifiedAt != null;
	}

	public boolean isPhoneVerified() {
		return phoneVerifiedAt != null;
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void markEmailVerified(Instant now) {
		if (emailVerifiedAt == null) {
			emailVerifiedAt = now;
			updatedAt = now;
		}
	}

	public void verifyPhone(String e164, Instant now) {
		this.phoneE164 = e164;
		this.phoneVerifiedAt = now;
		this.updatedAt = now;
	}

	public void changePassword(String newPasswordHash, Instant now) {
		this.passwordHash = newPasswordHash;
		this.failedLoginAttempts = 0;
		this.lockedUntil = null;
		this.updatedAt = now;
	}

	/**
	 * Records a failed login and locks the account for {@code lockDuration} once
	 * {@code maxAttempts} consecutive failures are reached.
	 */
	public void recordFailedLogin(Instant now, int maxAttempts, Duration lockDuration) {
		failedLoginAttempts++;
		if (failedLoginAttempts >= maxAttempts) {
			lockedUntil = now.plus(lockDuration);
			failedLoginAttempts = 0;
		}
		updatedAt = now;
	}

	public void recordSuccessfulLogin(Instant now) {
		if (failedLoginAttempts != 0 || lockedUntil != null) {
			failedLoginAttempts = 0;
			lockedUntil = null;
			updatedAt = now;
		}
	}

	public boolean grantPlatformRole(PlatformRole role, Instant now) {
		boolean added = platformRoles.add(role);
		if (added) {
			updatedAt = now;
		}
		return added;
	}

	public String getEmail() {
		return email;
	}

	public String getPhoneE164() {
		return phoneE164;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public UserStatus getStatus() {
		return status;
	}

	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public Set<PlatformRole> getPlatformRoles() {
		return Set.copyOf(platformRoles);
	}

}
