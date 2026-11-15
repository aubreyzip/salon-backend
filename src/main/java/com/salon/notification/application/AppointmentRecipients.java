package com.salon.notification.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.salon.booking.AppointmentEvent;
import com.salon.booking.AppointmentHistory;
import com.salon.notification.domain.NotificationKind;

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

/**
 * The recipient matrix of ADR-0014 decision 44, as a pure function of an event and the history
 * entry it names. It says which party gets which kind; {@link AppointmentNotifications} turns
 * parties into users (active accounts and memberships, without the actor).
 */
final class AppointmentRecipients {

	/** Who receives a notification, before it is resolved to a user. */
	enum Party {

		/** The account-linked customer (none for walk-in clients, decision 47). */
		CUSTOMER,

		/** The staff member assigned by the change. */
		STAFF,

		/** For reschedules to another staff member: the one assigned before. */
		PREVIOUS_STAFF,

		/** Active members holding {@code APPOINTMENT_MANAGE_ANY} for the branch. */
		DESK

	}

	record Target(Party party, NotificationKind kind) {
	}

	private AppointmentRecipients() {
	}

	static List<Target> targets(AppointmentEvent event, AppointmentHistory.Change change) {
		List<Target> targets = new ArrayList<>();
		boolean byCustomer = event.customerUserId() != null
				&& event.customerUserId().equals(change.actorUserId());
		boolean pending = "PENDING".equals(change.toStatus());
		switch (event) {
			case AppointmentEvent.Booked booked -> {
				if (!byCustomer) {
					add(targets, Party.CUSTOMER, BOOKED_BY_SALON);
					add(targets, Party.STAFF, STAFF_NEW_APPOINTMENT);
				}
				else if (pending) {
					add(targets, Party.CUSTOMER, BOOKING_REQUESTED);
					add(targets, Party.DESK, DESK_CONFIRMATION_NEEDED);
				}
				else {
					add(targets, Party.CUSTOMER, BOOKING_CONFIRMED);
					add(targets, Party.STAFF, STAFF_NEW_APPOINTMENT);
				}
			}
			case AppointmentEvent.Confirmed confirmed -> add(targets, Party.CUSTOMER, BOOKING_CONFIRMED);
			case AppointmentEvent.Rejected rejected -> add(targets, Party.CUSTOMER, BOOKING_REJECTED);
			case AppointmentEvent.Expired expired -> add(targets, Party.CUSTOMER, BOOKING_EXPIRED);
			case AppointmentEvent.Cancelled cancelled -> {
				add(targets, Party.CUSTOMER, cancelled.byCustomer() ? CANCELLED_BY_YOU : CANCELLED_BY_SALON);
				add(targets, Party.STAFF, STAFF_APPOINTMENT_CANCELLED);
			}
			case AppointmentEvent.Rescheduled rescheduled -> {
				add(targets, Party.CUSTOMER, byCustomer ? RESCHEDULED_BY_YOU : RESCHEDULED_BY_SALON);
				boolean staffChanged = change.previousStaffId() != null
						&& !Objects.equals(change.previousStaffId(), change.staffId());
				if (staffChanged) {
					add(targets, Party.PREVIOUS_STAFF, STAFF_APPOINTMENT_REASSIGNED);
					add(targets, Party.STAFF, STAFF_NEW_APPOINTMENT);
				}
				else {
					add(targets, Party.STAFF, STAFF_APPOINTMENT_RESCHEDULED);
				}
				if (byCustomer && pending) {
					add(targets, Party.DESK, DESK_CONFIRMATION_NEEDED);
				}
			}
			case AppointmentEvent.NeedsAttention attention -> add(targets, Party.DESK, DESK_NEEDS_ATTENTION);
			// Review invitations are Phase 6; staff tasks for open outcomes come with decision 52.
			case AppointmentEvent.Completed completed -> {
			}
			case AppointmentEvent.NoShow noShow -> {
			}
		}
		return targets;
	}

	private static void add(List<Target> targets, Party party, NotificationKind kind) {
		targets.add(new Target(party, kind));
	}

}
