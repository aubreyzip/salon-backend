package com.salon.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationChallengeTest {

	private static final Instant NOW = Instant.parse("2026-11-03T09:00:00Z");

	private static final int MAX_ATTEMPTS = 5;

	private final VerificationChallenge challenge = new VerificationChallenge(UUID.randomUUID(),
			ChallengePurpose.PHONE_VERIFICATION, ChallengeChannel.SMS, "+905321234567", "123456", NOW,
			NOW.plus(Duration.ofMinutes(5)));

	@Test
	void correctCodeConsumesTheChallenge() {
		assertThat(challenge.attempt("123456", NOW)).isTrue();
		assertThat(challenge.isUsable(NOW, MAX_ATTEMPTS)).isFalse();
	}

	@Test
	void wrongCodesCountTowardsTheAttemptLimit() {
		for (int i = 0; i < MAX_ATTEMPTS; i++) {
			assertThat(challenge.attempt("000000", NOW)).isFalse();
		}
		assertThat(challenge.isUsable(NOW, MAX_ATTEMPTS)).isFalse();
	}

	@Test
	void expiresAfterItsLifetime() {
		assertThat(challenge.isUsable(NOW.plus(Duration.ofMinutes(5)), MAX_ATTEMPTS)).isFalse();
		assertThat(challenge.isUsable(NOW.plus(Duration.ofMinutes(4)), MAX_ATTEMPTS)).isTrue();
	}

	@Test
	void invalidatedChallengeCannotBeUsed() {
		challenge.invalidate(NOW);
		assertThat(challenge.isUsable(NOW, MAX_ATTEMPTS)).isFalse();
	}

}
