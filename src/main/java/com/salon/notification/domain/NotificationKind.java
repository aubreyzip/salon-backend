package com.salon.notification.domain;

/**
 * What a notification announces, and to whom (ADR-0014 decision 44). Each kind has a template
 * ({@code notification/messages_*.properties}, keys {@code <KIND>.title} and {@code <KIND>.body}).
 * New kinds also need the {@code notification_kind_ck} constraint extended.
 */
public enum NotificationKind {

	/** The customer's own booking waits for the salon's confirmation. */
	BOOKING_REQUESTED(Audience.CUSTOMER, false, false),

	/** Booked by the customer and confirmed, immediately or later by the salon. */
	BOOKING_CONFIRMED(Audience.CUSTOMER, false, false),

	BOOKED_BY_SALON(Audience.CUSTOMER, false, false),

	BOOKING_REJECTED(Audience.CUSTOMER, true, false),

	BOOKING_EXPIRED(Audience.CUSTOMER, false, false),

	/** Confirmation of the customer's own cancellation. */
	CANCELLED_BY_YOU(Audience.CUSTOMER, false, false),

	CANCELLED_BY_SALON(Audience.CUSTOMER, true, false),

	/** Confirmation of the customer's own reschedule. */
	RESCHEDULED_BY_YOU(Audience.CUSTOMER, false, true),

	RESCHEDULED_BY_SALON(Audience.CUSTOMER, false, true),

	/** A new appointment in the staff member's book, also one moved to them from a colleague. */
	STAFF_NEW_APPOINTMENT(Audience.STAFF, false, false),

	STAFF_APPOINTMENT_CANCELLED(Audience.STAFF, true, false),

	/** Moved to another time, still with the same staff member. */
	STAFF_APPOINTMENT_RESCHEDULED(Audience.STAFF, false, true),

	/** Moved to another staff member: it left this staff member's book. */
	STAFF_APPOINTMENT_REASSIGNED(Audience.STAFF, false, true),

	/** A customer booking or reschedule waits for confirmation (an action for the desk). */
	DESK_CONFIRMATION_NEEDED(Audience.DESK, false, false),

	/** Flagged by an emergency override (ADR-0012 decisions 21, 25, 26). Never shown to customers. */
	DESK_NEEDS_ATTENTION(Audience.DESK, true, false);

	public enum Audience {

		/** The account-linked customer of the appointment. */
		CUSTOMER,

		/** The assigned staff member (or, for reschedules, the previous one). */
		STAFF,

		/** Members managing the branch's appointments ({@code APPOINTMENT_MANAGE_ANY}). */
		DESK

	}

	private final Audience audience;

	private final boolean showsReason;

	private final boolean showsPreviousStart;

	NotificationKind(Audience audience, boolean showsReason, boolean showsPreviousStart) {
		this.audience = audience;
		this.showsReason = showsReason;
		this.showsPreviousStart = showsPreviousStart;
	}

	public Audience audience() {
		return audience;
	}

	/** Whether the change's reason is part of the message (ADR-0013 decision 34). */
	public boolean showsReason() {
		return showsReason;
	}

	/** Whether the message names the start before the change (reschedules). */
	public boolean showsPreviousStart() {
		return showsPreviousStart;
	}

}
