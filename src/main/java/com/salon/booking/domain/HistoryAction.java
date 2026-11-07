package com.salon.booking.domain;

/** What happened to an appointment, as recorded in its history. */
public enum HistoryAction {

	BOOKED, CONFIRMED, REJECTED, EXPIRED, CANCELLED, COMPLETED, NO_SHOW, RESCHEDULED, ATTENTION_FLAGGED,
	ATTENTION_RESOLVED

}
