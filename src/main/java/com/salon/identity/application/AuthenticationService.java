package com.salon.identity.application;

import java.time.Clock;
import java.time.Instant;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.identity.domain.EmailAddresses;
import com.salon.identity.domain.RefreshToken;
import com.salon.identity.domain.RefreshTokenRepository;
import com.salon.identity.domain.RevokeReason;
import com.salon.identity.domain.SecureTokens;
import com.salon.identity.domain.UserAccount;
import com.salon.identity.domain.UserAccountRepository;
import com.salon.shared.ApiException;
import com.salon.shared.Ids;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh-token rotation with reuse detection, and logout (ADR-0003).
 * <p>
 * Transactions are not rolled back on {@link ApiException}: failed-login counters, lockouts and
 * family revocations must be committed even though the request fails.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class AuthenticationService {

	private static final String ENTITY_USER = "user";

	private final UserAccountRepository users;

	private final RefreshTokenRepository refreshTokens;

	private final TokenService tokens;

	private final PasswordEncoder passwordEncoder;

	private final AuditLog auditLog;

	private final IdentityProperties properties;

	private final Clock clock;

	/** Compared against when the email is unknown, so response times do not reveal registered emails. */
	private final String dummyPasswordHash;

	AuthenticationService(UserAccountRepository users, RefreshTokenRepository refreshTokens, TokenService tokens,
			PasswordEncoder passwordEncoder, AuditLog auditLog, IdentityProperties properties, Clock clock) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.auditLog = auditLog;
		this.properties = properties;
		this.clock = clock;
		this.dummyPasswordHash = passwordEncoder.encode(SecureTokens.newRefreshToken());
	}

	public AuthTokens login(String email, String password) {
		Instant now = clock.instant();
		UserAccount user = users.findByEmail(EmailAddresses.normalize(email)).orElse(null);
		if (user == null) {
			passwordEncoder.matches(password, dummyPasswordHash);
			throw IdentityErrors.invalidCredentials();
		}
		if (user.isLocked(now)) {
			throw IdentityErrors.loginLocked();
		}
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			user.recordFailedLogin(now, properties.maxFailedLogins(), properties.loginLockDuration());
			auditLog.record(AuditRecord.of(null, "identity.login.failed", ENTITY_USER, user.getId()));
			throw IdentityErrors.invalidCredentials();
		}
		if (!user.isActive()) {
			throw IdentityErrors.accountDisabled();
		}
		if (!user.isEmailVerified()) {
			throw IdentityErrors.emailNotVerified();
		}

		user.recordSuccessfulLogin(now);
		if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
			user.changePassword(passwordEncoder.encode(password), now);
		}
		auditLog.record(AuditRecord.of(user.getId(), "identity.login.succeeded", ENTITY_USER, user.getId()));
		return tokens.issue(user, Ids.newId(), now);
	}

	public AuthTokens refresh(String refreshToken) {
		Instant now = clock.instant();
		RefreshToken token = refreshTokens.findByTokenHash(SecureTokens.sha256(refreshToken))
			.orElseThrow(IdentityErrors::invalidRefreshToken);

		if (token.isRevoked()) {
			if (token.getRevokeReason() == RevokeReason.ROTATED) {
				// A rotated token was presented again: assume theft and end the whole session.
				tokens.revokeFamily(token.getFamilyId(), RevokeReason.REUSE_DETECTED, now);
				auditLog.record(AuditRecord.of(token.getUserId(), "identity.refresh_token.reuse_detected",
						"refresh_token_family", token.getFamilyId()));
			}
			throw IdentityErrors.invalidRefreshToken();
		}
		if (token.isExpired(now)) {
			throw IdentityErrors.invalidRefreshToken();
		}

		UserAccount user = users.findById(token.getUserId()).orElseThrow(IdentityErrors::invalidRefreshToken);
		if (!user.isActive()) {
			tokens.revokeFamily(token.getFamilyId(), RevokeReason.LOGOUT, now);
			throw IdentityErrors.invalidRefreshToken();
		}

		token.revoke(RevokeReason.ROTATED, now);
		return tokens.issue(user, token.getFamilyId(), now);
	}

	public void logout(String refreshToken) {
		Instant now = clock.instant();
		refreshTokens.findByTokenHash(SecureTokens.sha256(refreshToken)).ifPresent(token -> {
			tokens.revokeFamily(token.getFamilyId(), RevokeReason.LOGOUT, now);
			auditLog.record(AuditRecord.of(token.getUserId(), "identity.logout", ENTITY_USER, token.getUserId()));
		});
	}

}
