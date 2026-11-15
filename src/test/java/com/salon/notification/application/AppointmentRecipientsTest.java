package com.salon.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.booking.AppointmentEvent;
import com.salon.booking.AppointmentHistory.Change;
import com.salon.notification.application.AppointmentRecipients.Party;
import com.salon.notification.application.AppointmentRecipients.Target;
import org.junit.jupiter.api.Test;

import static com.salon.notification.domain.NotificationKind.BOOKED_BY_SALON;
import static com.salon.notification.domain.NotificationKind.BOOKING_CONFIRMED;
import static com.salon.notification.domain.NotificationKind.BOOKING_EXPIRED;
import static com.salon.notification.domain.NotificationKind.BOOKING_REJECTED;
import static com.salon.notification.domain.NotificationKind.BOOKING_REQUESTED;
import static com.salon.notification.domain.NotificationKind.CANCELLED_BY_SALON;
import static com.salon.notification.domain.NotificationKind.CANCELLED_BY_YOU;
import static com.salon.notification.domain.NotificationKind.DESK_CONFIRMATION_NEEDED;
import static com.salon.notification.domain.NotificationKind.DESK_NEEDS_ATTENTION;
import static com.salon.notification.domain.NotificationKind.RESCHEDULED_BY_SALON;
import static com.salon.notification.domain.NotificationKind.RESCHEDULED_BY_YOU;
import static com.salon.notification.domain.NotificationKind.STAFF_APPOINTMENT_CANCELLED;
import static com.salon.notification.domain.NotificationKind.STAFF_APPOINTMENT_REASSIGNED;
import static com.salon.notification.domain.NotificationKind.STAFF_APPOINTMENT_RESCHEDULED;
import static com.salon.notification.domain.NotificationKind.STAFF_NEW_APPOINTMENT;
import static org.assertj.core.api.Assertions.assertThat;

/** The recipient matrix of ADR-0014 decision 44, row by row. */
class AppointmentRecipientsTest {

	private static final UUID CUSTOMER = UUID.randomUUID();

	private static final UUID MEMBER = UUID.randomUUID();

	private static final UUID AYSE = UUID.randomUUID();

	private static final UUID MEHMET = UUID.randomUUID();

	private static final Instant START = Instant.parse("2026-12-01T07:00:00Z");

	@Test
	void customerBookingThatNeedsConfirmation() {
		assertThat(targets(booked(CUSTOMER), change("BOOKED", "PENDING", CUSTOMER)))
			.containsExactly(target(Party.CUSTOMER, BOOKING_REQUESTED), target(Party.DESK, DESK_CONFIRMATION_NEEDED));
	}

	@Test
	void customerBookingConfirmedImmediately() {
		assertThat(targets(booked(CUSTOMER), change("BOOKED", "CONFIRMED", CUSTOMER)))
			.containsExactly(target(Party.CUSTOMER, BOOKING_CONFIRMED), target(Party.STAFF, STAFF_NEW_APPOINTMENT));
	}

	@Test
	void salonBooking() {
		assertThat(targets(booked(CUSTOMER), change("BOOKED", "CONFIRMED", MEMBER)))
			.containsExactly(target(Party.CUSTOMER, BOOKED_BY_SALON), target(Party.STAFF, STAFF_NEW_APPOINTMENT));
	}

	@Test
	void salonDecisionsAndExpiryGoToTheCustomerOnly() {
		var ids = ids();
		assertThat(targets(new AppointmentEvent.Confirmed(ids[0], ids[1], ids[2], ids[3], CUSTOMER),
				change("CONFIRMED", "CONFIRMED", MEMBER)))
			.containsExactly(target(Party.CUSTOMER, BOOKING_CONFIRMED));
		assertThat(targets(new AppointmentEvent.Rejected(ids[0], ids[1], ids[2], ids[3], CUSTOMER),
				change("REJECTED", "REJECTED", MEMBER)))
			.containsExactly(target(Party.CUSTOMER, BOOKING_REJECTED));
		assertThat(targets(new AppointmentEvent.Expired(ids[0], ids[1], ids[2], ids[3], CUSTOMER),
				change("EXPIRED", "EXPIRED", null)))
			.containsExactly(target(Party.CUSTOMER, BOOKING_EXPIRED));
	}

	@Test
	void cancellations() {
		var ids = ids();
		assertThat(targets(new AppointmentEvent.Cancelled(ids[0], ids[1], ids[2], ids[3], CUSTOMER, true),
				change("CANCELLED", "CANCELLED", CUSTOMER)))
			.containsExactly(target(Party.CUSTOMER, CANCELLED_BY_YOU), target(Party.STAFF, STAFF_APPOINTMENT_CANCELLED));
		assertThat(targets(new AppointmentEvent.Cancelled(ids[0], ids[1], ids[2], ids[3], CUSTOMER, false),
				change("CANCELLED", "CANCELLED", MEMBER)))
			.containsExactly(target(Party.CUSTOMER, CANCELLED_BY_SALON),
					target(Party.STAFF, STAFF_APPOINTMENT_CANCELLED));
	}

	@Test
	void customerRescheduleBackToPendingNeedsTheDesk() {
		assertThat(targets(rescheduled(), reschedule("PENDING", CUSTOMER, AYSE, AYSE)))
			.containsExactly(target(Party.CUSTOMER, RESCHEDULED_BY_YOU),
					target(Party.STAFF, STAFF_APPOINTMENT_RESCHEDULED), target(Party.DESK, DESK_CONFIRMATION_NEEDED));
		assertThat(targets(rescheduled(), reschedule("CONFIRMED", CUSTOMER, AYSE, AYSE)))
			.containsExactly(target(Party.CUSTOMER, RESCHEDULED_BY_YOU),
					target(Party.STAFF, STAFF_APPOINTMENT_RESCHEDULED));
	}

	@Test
	void rescheduleToAnotherStaffMemberTellsBoth() {
		assertThat(targets(rescheduled(), reschedule("CONFIRMED", MEMBER, AYSE, MEHMET)))
			.containsExactly(target(Party.CUSTOMER, RESCHEDULED_BY_SALON),
					target(Party.PREVIOUS_STAFF, STAFF_APPOINTMENT_REASSIGNED),
					target(Party.STAFF, STAFF_NEW_APPOINTMENT));
	}

	@Test
	void salonRescheduleOfAPendingAppointmentDoesNotAskTheDesk() {
		assertThat(targets(rescheduled(), reschedule("PENDING", MEMBER, AYSE, AYSE)))
			.extracting(Target::party)
			.doesNotContain(Party.DESK);
	}

	@Test
	void attentionFlagsGoToTheDeskOnly() {
		var ids = ids();
		assertThat(targets(new AppointmentEvent.NeedsAttention(ids[0], ids[1], ids[2], ids[3], CUSTOMER),
				change("ATTENTION_FLAGGED", "CONFIRMED", MEMBER)))
			.containsExactly(target(Party.DESK, DESK_NEEDS_ATTENTION));
	}

	@Test
	void outcomesNotifyNobody() {
		var ids = ids();
		assertThat(targets(new AppointmentEvent.Completed(ids[0], ids[1], ids[2], ids[3], CUSTOMER),
				change("COMPLETED", "COMPLETED", MEMBER)))
			.isEmpty();
		assertThat(targets(new AppointmentEvent.NoShow(ids[0], ids[1], ids[2], ids[3], CUSTOMER),
				change("NO_SHOW", "NO_SHOW", MEMBER)))
			.isEmpty();
	}

	@Test
	void walkInBookingsAreNeverTreatedAsTheCustomersOwn() {
		// Walk-in clients have no account (decision 47); a salon booking for one is a salon booking.
		assertThat(targets(booked(null), change("BOOKED", "CONFIRMED", MEMBER)))
			.containsExactly(target(Party.CUSTOMER, BOOKED_BY_SALON), target(Party.STAFF, STAFF_NEW_APPOINTMENT));
	}

	private static List<Target> targets(AppointmentEvent event, Change change) {
		return AppointmentRecipients.targets(event, change);
	}

	private static Target target(Party party, com.salon.notification.domain.NotificationKind kind) {
		return new Target(party, kind);
	}

	private static UUID[] ids() {
		return new UUID[] { UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() };
	}

	private static AppointmentEvent.Booked booked(UUID customer) {
		var ids = ids();
		return new AppointmentEvent.Booked(ids[0], ids[1], ids[2], ids[3], customer);
	}

	private static AppointmentEvent.Rescheduled rescheduled() {
		var ids = ids();
		return new AppointmentEvent.Rescheduled(ids[0], ids[1], ids[2], ids[3], CUSTOMER);
	}

	private static Change change(String action, String toStatus, UUID actor) {
		return new Change(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), action, null, toStatus, actor, null,
				START, START.plusSeconds(3600), AYSE, null, null, null, START.minusSeconds(86400));
	}

	private static Change reschedule(String toStatus, UUID actor, UUID previousStaff, UUID staff) {
		return new Change(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RESCHEDULED", "CONFIRMED", toStatus,
				actor, null, START, START.plusSeconds(3600), staff, START.minusSeconds(3600), START, previousStaff,
				START.minusSeconds(86400));
	}

}
