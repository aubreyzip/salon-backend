/**
 * Notifications (ADR-0014, ADR-0011): turns domain events into persistent in-app notifications,
 * and later into push, SMS and email deliveries through the outbox. Nothing depends on this module.
 * <p>
 * Appointment notifications are built from the immutable history entry each
 * {@link com.salon.booking.AppointmentEvent} names (decision 56), for the recipients of the matrix
 * in decision 44.
 */
package com.salon.notification;
