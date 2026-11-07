package com.salon.booking.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Appointment lifecycle (report §7, ADR-0009, ADR-0013). Only {@link #PENDING} and
 * {@link #CONFIRMED} are blocking: they hold the slot (exclusion constraints, ADR-0005) and count
 * toward the customer limit (ADR-0012 decision 24).
 */
public enum AppointmentStatus {

	PENDING, CONFIRMED, REJECTED, EXPIRED, CANCELLED, COMPLETED, NO_SHOW;

	public static final Set<AppointmentStatus> BLOCKING = EnumSet.of(PENDING, CONFIRMED);

	public boolean isBlocking() {
		return BLOCKING.contains(this);
	}

}
