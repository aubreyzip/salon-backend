package com.salon.identity.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountTest {

	private static final Instant NOW = Instant.parse("2026-11-03T09:00:00Z");

	private static final Duration LOCK = Duration.ofMinutes(15);

	private final UserAccount user = new UserAccount("user@example.com", "{bcrypt}hash", "User", NOW);

	@Test
	void locksAfterTheMaximumNumberOfConsecutiveFailures() {
		for (int i = 0; i < 4; i++) {
			user.recordFailedLogin(NOW, 5, LOCK);
		}
		assertThat(user.isLocked(NOW)).isFalse();

		user.recordFailedLogin(NOW, 5, LOCK);

		assertThat(user.isLocked(NOW)).isTrue();
		assertThat(user.isLocked(NOW.plus(LOCK))).isFalse();
	}

	@Test
	void successfulLoginResetsTheFailureCounter() {
		for (int i = 0; i < 4; i++) {
			user.recordFailedLogin(NOW, 5, LOCK);
		}
		user.recordSuccessfulLogin(NOW);
		user.recordFailedLogin(NOW, 5, LOCK);

		assertThat(user.isLocked(NOW)).isFalse();
	}

	@Test
	void passwordChangeClearsALock() {
		for (int i = 0; i < 5; i++) {
			user.recordFailedLogin(NOW, 5, LOCK);
		}
		user.changePassword("{bcrypt}new", NOW);

		assertThat(user.isLocked(NOW)).isFalse();
	}

}
