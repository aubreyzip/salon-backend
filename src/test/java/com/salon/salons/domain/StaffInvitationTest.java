package com.salon.salons.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaffInvitationTest {

	private static final Instant CREATED = Instant.parse("2026-11-03T09:00:00Z");

	private static final Instant EXPIRES = CREATED.plus(Duration.ofDays(7));

	private final StaffInvitation invitation = new StaffInvitation(UUID.randomUUID(), UUID.randomUUID(),
			"invitee@example.com", "hash", UUID.randomUUID(), CREATED, EXPIRES);

	@Test
	void isOpenUntilJustBeforeExpiry() {
		assertThat(invitation.isOpen(CREATED)).isTrue();
		assertThat(invitation.isOpen(EXPIRES.minusNanos(1))).isTrue();
	}

	@Test
	void isClosedExactlyAtAndAfterExpiry() {
		assertThat(invitation.isOpen(EXPIRES)).isFalse();
		assertThat(invitation.isOpen(EXPIRES.plusSeconds(1))).isFalse();
	}

	@Test
	void acceptedInvitationIsClosedAndCannotBeRevokedAfterwards() {
		invitation.accept(UUID.randomUUID(), CREATED);
		invitation.revoke(CREATED);

		assertThat(invitation.isOpen(CREATED)).isFalse();
	}

	@Test
	void revokedInvitationIsClosed() {
		invitation.revoke(CREATED);

		assertThat(invitation.isOpen(CREATED)).isFalse();
	}

}
