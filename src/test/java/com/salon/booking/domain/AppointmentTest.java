package com.salon.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.shared.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The appointment lifecycle (report §7, ADR-0009, ADR-0013): allowed moves only.
 */
class AppointmentTest {

	private static final Instant START = Instant.parse("2030-01-07T08:00:00Z");

	private static final Instant BEFORE = START.minusSeconds(3600);

	private static final List<AppointmentItem> ITEMS = List.of(
			new AppointmentItem(UUID.randomUUID(), "Cut", 45, new BigDecimal("450.00"), "TRY", false),
			new AppointmentItem(UUID.randomUUID(), "Wash", 15, new BigDecimal("100.00"), "TRY", false));

	@Test
	void theStaffMemberIsBusyUntilTheEndPlusTheBuffer() {
		Appointment appointment = confirmed();

		assertThat(appointment.getEndAt()).isEqualTo(START.plusSeconds(3600));
		assertThat(appointment.getBlockEndAt()).isEqualTo(START.plusSeconds(3600 + 600));
		assertThat(appointment.serviceIds()).hasSize(2);
	}

	@Test
	void pendingAppointmentsAreConfirmedOrRejectedBeforeTheirDeadline() {
		Appointment confirmedInTime = pending(START);
		confirmedInTime.confirm(BEFORE);
		assertThat(confirmedInTime.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
		assertThat(confirmedInTime.getConfirmationDeadline()).isNull();

		Appointment late = pending(BEFORE);
		assertThatThrownBy(() -> late.confirm(BEFORE)).isInstanceOf(ApiException.class);
		late.expire(BEFORE);
		assertThat(late.getStatus()).isEqualTo(AppointmentStatus.EXPIRED);
		assertThatThrownBy(() -> late.cancel(CancelledBy.CUSTOMER, null, BEFORE)).isInstanceOf(ApiException.class);
	}

	@Test
	void onlyPendingAppointmentsCanBeConfirmedRejectedOrExpired() {
		Appointment appointment = confirmed();

		assertThatThrownBy(() -> appointment.confirm(BEFORE)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> appointment.reject(null, BEFORE)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> appointment.expire(BEFORE)).isInstanceOf(ApiException.class);
	}

	@Test
	void outcomesNeedAStartedConfirmedAppointmentAndAreFinal() {
		Appointment appointment = confirmed();

		assertThatThrownBy(() -> appointment.complete(BEFORE)).hasMessageContaining("started");
		appointment.markNoShow(START);
		assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
		assertThatThrownBy(() -> appointment.complete(START)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> pending(START).complete(START)).isInstanceOf(ApiException.class);
	}

	@Test
	void reschedulingMovesTheAppointmentAndMayRequireConfirmationAgain() {
		Appointment appointment = confirmed();
		UUID otherStaff = UUID.randomUUID();
		Instant later = START.plusSeconds(7200);

		appointment.reschedule(otherStaff, later, List.of(ITEMS.getFirst().withDuration(30)), 0, later, BEFORE);

		assertThat(appointment.getStaffId()).isEqualTo(otherStaff);
		assertThat(appointment.getEndAt()).isEqualTo(later.plusSeconds(1800));
		assertThat(appointment.getBlockEndAt()).isEqualTo(appointment.getEndAt());
		assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING);
	}

	@Test
	void theAttentionFlagIsKeptUntilRescheduledCancelledOrResolved() {
		Appointment appointment = confirmed();
		appointment.flagForAttention("Closed for a burst pipe", UUID.randomUUID(), BEFORE);
		assertThat(appointment.needsAttention()).isTrue();
		assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);

		appointment.resolveAttention(BEFORE);
		assertThat(appointment.needsAttention()).isFalse();
		assertThatThrownBy(() -> appointment.resolveAttention(BEFORE)).isInstanceOf(ApiException.class);

		appointment.flagForAttention("Again", UUID.randomUUID(), BEFORE);
		appointment.cancel(CancelledBy.SALON, "Closed", BEFORE);
		assertThat(appointment.needsAttention()).isFalse();
		assertThat(appointment.getCancelledBy()).isEqualTo(CancelledBy.SALON);
	}

	@Test
	void customersMayChangeAppointmentsOnlyBeforeTheCutoff() {
		Appointment appointment = confirmed();

		assertThat(appointment.customerMayChange(START.minusSeconds(7201), 120)).isTrue();
		assertThat(appointment.customerMayChange(START.minusSeconds(7200), 120)).isFalse();
	}

	private static Appointment confirmed() {
		return new Appointment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
				AppointmentSource.SALON, START, ITEMS, 10, null, null, null, UUID.randomUUID(), BEFORE);
	}

	private static Appointment pending(Instant deadline) {
		return new Appointment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), AppointmentSource.ONLINE, START, ITEMS, 10, deadline, null, null, UUID.randomUUID(),
				BEFORE);
	}

}
