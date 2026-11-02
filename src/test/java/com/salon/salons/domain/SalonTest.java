package com.salon.salons.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import com.salon.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalonTest {

	private static final Instant NOW = Instant.parse("2026-11-03T09:00:00Z");

	private final Salon salon = new Salon("Test", "test", null, UUID.randomUUID(), NOW);

	@Test
	void fullReviewCycle() {
		assertThat(salon.getStatus()).isEqualTo(SalonStatus.DRAFT);

		salon.submitForReview(NOW);
		salon.reject("Add photos", NOW);
		assertThat(salon.getStatus()).isEqualTo(SalonStatus.DRAFT);
		assertThat(salon.getReviewNote()).isEqualTo("Add photos");

		salon.submitForReview(NOW);
		assertThat(salon.getReviewNote()).isNull();
		salon.approve(NOW);
		assertThat(salon.getStatus()).isEqualTo(SalonStatus.ACTIVE);

		salon.suspend("Complaints", NOW);
		assertThat(salon.getStatus()).isEqualTo(SalonStatus.SUSPENDED);
		salon.reactivate(NOW);
		assertThat(salon.getStatus()).isEqualTo(SalonStatus.ACTIVE);
	}

	enum Action {

		SUBMIT(SalonStatus.DRAFT, SalonStatus.PENDING_REVIEW), APPROVE(SalonStatus.PENDING_REVIEW, SalonStatus.ACTIVE),
		REJECT(SalonStatus.PENDING_REVIEW, SalonStatus.DRAFT), SUSPEND(SalonStatus.ACTIVE, SalonStatus.SUSPENDED),
		REACTIVATE(SalonStatus.SUSPENDED, SalonStatus.ACTIVE);

		final SalonStatus allowedFrom;

		final SalonStatus result;

		Action(SalonStatus allowedFrom, SalonStatus result) {
			this.allowedFrom = allowedFrom;
			this.result = result;
		}

		void apply(Salon salon) {
			switch (this) {
				case SUBMIT -> salon.submitForReview(NOW);
				case APPROVE -> salon.approve(NOW);
				case REJECT -> salon.reject("note", NOW);
				case SUSPEND -> salon.suspend("note", NOW);
				case REACTIVATE -> salon.reactivate(NOW);
			}
		}

	}

	/** Every action from every status: exactly one source status is allowed per action. */
	@ParameterizedTest(name = "{1} from {0}")
	@MethodSource("statusesAndActions")
	void transitionTable(SalonStatus from, Action action) {
		Salon subject = salonIn(from);

		if (from == action.allowedFrom) {
			action.apply(subject);
			assertThat(subject.getStatus()).isEqualTo(action.result);
		}
		else {
			assertThatThrownBy(() -> action.apply(subject)).isInstanceOf(ApiException.class)
				.extracting("code")
				.isEqualTo("INVALID_SALON_STATUS");
			assertThat(subject.getStatus()).isEqualTo(from);
		}
	}

	static Stream<Arguments> statusesAndActions() {
		return Arrays.stream(SalonStatus.values())
			.flatMap(status -> Arrays.stream(Action.values()).map(action -> Arguments.of(status, action)));
	}

	private static Salon salonIn(SalonStatus status) {
		Salon salon = new Salon("Test", "test", null, UUID.randomUUID(), NOW);
		if (status == SalonStatus.DRAFT) {
			return salon;
		}
		salon.submitForReview(NOW);
		if (status == SalonStatus.PENDING_REVIEW) {
			return salon;
		}
		salon.approve(NOW);
		if (status == SalonStatus.SUSPENDED) {
			salon.suspend("note", NOW);
		}
		return salon;
	}

	@Test
	void suspendedSalonsCannotBeEdited() {
		salon.submitForReview(NOW);
		salon.approve(NOW);
		salon.suspend("Fraud check", NOW);

		assertThatThrownBy(() -> salon.update("New name", null, NOW)).isInstanceOf(ApiException.class)
			.extracting("code")
			.isEqualTo("SALON_SUSPENDED");
	}

}
