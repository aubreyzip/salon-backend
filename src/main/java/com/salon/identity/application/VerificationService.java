package com.salon.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.salon.identity.domain.ChallengeChannel;
import com.salon.identity.domain.ChallengePurpose;
import com.salon.identity.domain.SecureTokens;
import com.salon.identity.domain.UserAccount;
import com.salon.identity.domain.VerificationChallenge;
import com.salon.identity.domain.VerificationChallengeRepository;
import com.salon.messaging.EmailGateway;
import com.salon.messaging.EmailMessage;
import com.salon.messaging.SmsGateway;
import com.salon.messaging.SmsMessage;
import com.salon.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and checks one-time codes, with resend intervals and hourly limits per user and per
 * phone number (protection against SMS pumping).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
class VerificationService {

	private static final int CODE_DIGITS = 6;

	private static final Duration RATE_WINDOW = Duration.ofHours(1);

	private final VerificationChallengeRepository challenges;

	private final EmailGateway emailGateway;

	private final SmsGateway smsGateway;

	private final IdentityProperties properties;

	private final Clock clock;

	VerificationService(VerificationChallengeRepository challenges, EmailGateway emailGateway, SmsGateway smsGateway,
			IdentityProperties properties, Clock clock) {
		this.challenges = challenges;
		this.emailGateway = emailGateway;
		this.smsGateway = smsGateway;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Creates a new code, invalidates earlier unused codes for the same purpose and sends it.
	 * @throws ApiException with {@code VERIFICATION_RATE_LIMITED} when limits are exceeded
	 */
	void issue(UserAccount user, ChallengePurpose purpose, ChallengeChannel channel, String target) {
		Instant now = clock.instant();
		enforceRateLimits(user.getId(), purpose, channel, target, now);

		challenges.findByUserIdAndPurposeAndConsumedAtIsNull(user.getId(), purpose)
			.forEach(previous -> previous.invalidate(now));

		Duration ttl = channel == ChallengeChannel.SMS ? properties.smsCodeTtl() : properties.emailCodeTtl();
		String code = SecureTokens.newNumericCode(CODE_DIGITS);
		challenges.save(new VerificationChallenge(user.getId(), purpose, channel, target, code, now, now.plus(ttl)));

		switch (channel) {
			case EMAIL -> emailGateway.send(emailFor(purpose, target, code, ttl));
			case SMS -> smsGateway.send(new SmsMessage(target, "Salon verification code: " + code
					+ ". Do not share this code with anyone."));
		}
	}

	/**
	 * Checks a code against the latest challenge. Failed attempts are counted, so callers must not
	 * roll back their transaction when verification fails.
	 */
	Optional<VerificationChallenge> verify(UUID userId, ChallengePurpose purpose, String code) {
		Instant now = clock.instant();
		VerificationChallenge challenge = challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose)
			.orElse(null);
		if (challenge == null || !challenge.isUsable(now, properties.maxCodeAttempts())) {
			return Optional.empty();
		}
		return challenge.attempt(code, now) ? Optional.of(challenge) : Optional.empty();
	}

	private void enforceRateLimits(UUID userId, ChallengePurpose purpose, ChallengeChannel channel, String target,
			Instant now) {
		boolean resentTooSoon = challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose)
			.map(latest -> latest.getCreatedAt().plus(properties.codeResendInterval()).isAfter(now))
			.orElse(false);
		Instant windowStart = now.minus(RATE_WINDOW);
		boolean tooManyForUser = challenges.countByUserIdAndPurposeAndCreatedAtAfter(userId, purpose,
				windowStart) >= properties.maxCodesPerHour();
		boolean tooManyForTarget = channel == ChallengeChannel.SMS
				&& challenges.countByTargetAndCreatedAtAfter(target, windowStart) >= properties.maxCodesPerHour();
		if (resentTooSoon || tooManyForUser || tooManyForTarget) {
			throw IdentityErrors.verificationRateLimited();
		}
	}

	private static EmailMessage emailFor(ChallengePurpose purpose, String to, String code, Duration ttl) {
		long minutes = ttl.toMinutes();
		return switch (purpose) {
			case PASSWORD_RESET -> new EmailMessage(to, "Reset your Salon password", "Your password reset code is "
					+ code + ". It expires in " + minutes + " minutes. If you did not request this, ignore this email.");
			default -> new EmailMessage(to, "Verify your Salon account",
					"Your verification code is " + code + ". It expires in " + minutes + " minutes.");
		};
	}

}
