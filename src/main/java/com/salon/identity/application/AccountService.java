package com.salon.identity.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.identity.domain.ChallengeChannel;
import com.salon.identity.domain.ChallengePurpose;
import com.salon.identity.domain.RevokeReason;
import com.salon.identity.domain.UserAccount;
import com.salon.identity.domain.UserAccountRepository;
import com.salon.messaging.EmailGateway;
import com.salon.messaging.EmailMessage;
import com.salon.shared.ApiException;
import com.salon.shared.EmailAddresses;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, email verification and password reset.
 * <p>
 * Registration and "forgot password" always behave the same towards the caller, whether or not an
 * account exists, so they cannot be used to discover registered email addresses.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class AccountService {

	private static final int BCRYPT_MAX_BYTES = 72;

	private static final String ENTITY_USER = "user";

	private final UserAccountRepository users;

	private final VerificationService verification;

	private final TokenService tokens;

	private final PasswordEncoder passwordEncoder;

	private final EmailGateway emailGateway;

	private final AuditLog auditLog;

	private final Clock clock;

	AccountService(UserAccountRepository users, VerificationService verification, TokenService tokens,
			PasswordEncoder passwordEncoder, EmailGateway emailGateway, AuditLog auditLog, Clock clock) {
		this.users = users;
		this.verification = verification;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.emailGateway = emailGateway;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	public void register(String email, String password, String displayName) {
		checkPasswordLength(password);
		Instant now = clock.instant();
		String normalizedEmail = EmailAddresses.normalize(email);
		UserAccount existing = users.findByEmail(normalizedEmail).orElse(null);

		if (existing == null) {
			UserAccount user = users.save(
					new UserAccount(normalizedEmail, passwordEncoder.encode(password), displayName.strip(), now));
			auditLog.record(AuditRecord.of(user.getId(), "identity.user.registered", ENTITY_USER, user.getId()));
			issueQuietly(user, ChallengePurpose.EMAIL_VERIFICATION);
		}
		else if (existing.isEmailVerified()) {
			emailGateway.send(new EmailMessage(normalizedEmail, "Your Salon account",
					"Someone tried to register with this email address, but it already has an account. "
							+ "If this was you, sign in or reset your password."));
			auditLog.record(AuditRecord.of(null, "identity.registration.duplicate", ENTITY_USER, existing.getId()));
		}
		else {
			// Unverified account: resend the code, but never change the stored password.
			issueQuietly(existing, ChallengePurpose.EMAIL_VERIFICATION);
		}
	}

	public void resendEmailVerification(String email) {
		users.findByEmail(EmailAddresses.normalize(email))
			.filter(user -> !user.isEmailVerified())
			.ifPresent(user -> issueQuietly(user, ChallengePurpose.EMAIL_VERIFICATION));
	}

	public void verifyEmail(String email, String code) {
		UserAccount user = users.findByEmail(EmailAddresses.normalize(email))
			.filter(candidate -> !candidate.isEmailVerified())
			.orElseThrow(IdentityErrors::invalidOrExpiredCode);
		verification.verify(user.getId(), ChallengePurpose.EMAIL_VERIFICATION, code)
			.orElseThrow(IdentityErrors::invalidOrExpiredCode);
		user.markEmailVerified(clock.instant());
		auditLog.record(AuditRecord.of(user.getId(), "identity.email.verified", ENTITY_USER, user.getId()));
	}

	public void requestPasswordReset(String email) {
		users.findByEmail(EmailAddresses.normalize(email)).filter(UserAccount::isActive).ifPresent(user -> {
			issueQuietly(user, ChallengePurpose.PASSWORD_RESET);
			auditLog.record(
					AuditRecord.of(null, "identity.password.reset_requested", ENTITY_USER, user.getId()));
		});
	}

	public void resetPassword(String email, String code, String newPassword) {
		checkPasswordLength(newPassword);
		UserAccount user = users.findByEmail(EmailAddresses.normalize(email))
			.orElseThrow(IdentityErrors::invalidOrExpiredCode);
		verification.verify(user.getId(), ChallengePurpose.PASSWORD_RESET, code)
			.orElseThrow(IdentityErrors::invalidOrExpiredCode);

		Instant now = clock.instant();
		user.changePassword(passwordEncoder.encode(newPassword), now);
		// The code proved ownership of the mailbox.
		user.markEmailVerified(now);
		tokens.revokeAllForUser(user.getId(), RevokeReason.PASSWORD_CHANGED, now);
		auditLog.record(AuditRecord.of(user.getId(), "identity.password.reset", ENTITY_USER, user.getId()));
	}

	/** Sends a code by email; silently skips when rate-limited so responses stay uniform. */
	private void issueQuietly(UserAccount user, ChallengePurpose purpose) {
		try {
			verification.issue(user, purpose, ChallengeChannel.EMAIL, user.getEmail());
		}
		catch (ApiException rateLimited) {
			auditLog.record(AuditRecord.of(null, "identity.verification.rate_limited", ENTITY_USER, user.getId()));
		}
	}

	private static void checkPasswordLength(String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw IdentityErrors.passwordTooLong();
		}
	}

}
