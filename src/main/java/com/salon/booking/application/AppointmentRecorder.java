package com.salon.booking.application;

import java.time.Clock;
import java.util.UUID;

import com.salon.booking.AppointmentEvent;
import com.salon.booking.domain.Appointment;
import com.salon.booking.domain.AppointmentHistoryEntry;
import com.salon.booking.domain.AppointmentHistoryRepository;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.booking.domain.CancelledBy;
import com.salon.booking.domain.HistoryAction;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an appointment's history entry and publishes the matching domain event, in the
 * transaction of the change (ADR-0009, ADR-0013 decision 39).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class AppointmentRecorder {

	private final AppointmentHistoryRepository history;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	AppointmentRecorder(AppointmentHistoryRepository history, ApplicationEventPublisher events, Clock clock) {
		this.history = history;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * @param fromStatus the status before the change ({@code null} when booked)
	 * @param actorUserId who made the change, {@code null} for the system
	 */
	void record(Appointment appointment, HistoryAction action, AppointmentStatus fromStatus, UUID actorUserId,
			String reason) {
		history.save(new AppointmentHistoryEntry(appointment, action, fromStatus, actorUserId, reason, clock.instant()));
		AppointmentEvent event = event(appointment, action);
		if (event != null) {
			events.publishEvent(event);
		}
	}

	private static AppointmentEvent event(Appointment a, HistoryAction action) {
		UUID id = a.getId();
		UUID salonId = a.getSalonId();
		UUID branchId = a.getBranchId();
		UUID customer = a.getCustomerUserId();
		return switch (action) {
			case BOOKED -> new AppointmentEvent.Booked(id, salonId, branchId, customer);
			case CONFIRMED -> new AppointmentEvent.Confirmed(id, salonId, branchId, customer);
			case REJECTED -> new AppointmentEvent.Rejected(id, salonId, branchId, customer);
			case EXPIRED -> new AppointmentEvent.Expired(id, salonId, branchId, customer);
			case CANCELLED -> new AppointmentEvent.Cancelled(id, salonId, branchId, customer,
					a.getCancelledBy() == CancelledBy.CUSTOMER);
			case RESCHEDULED -> new AppointmentEvent.Rescheduled(id, salonId, branchId, customer);
			case COMPLETED -> new AppointmentEvent.Completed(id, salonId, branchId, customer);
			case NO_SHOW -> new AppointmentEvent.NoShow(id, salonId, branchId, customer);
			case ATTENTION_FLAGGED -> new AppointmentEvent.NeedsAttention(id, salonId, branchId, customer);
			case ATTENTION_RESOLVED -> null; // internal bookkeeping, nobody to notify
		};
	}

}
