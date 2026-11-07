package com.salon.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.salon.booking.domain.Appointment;
import com.salon.booking.domain.AppointmentRepository;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.booking.domain.HistoryAction;
import com.salon.salons.BlockingAppointment;
import com.salon.salons.BlockingAppointments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking's side of the extension point (ADR-0012 decisions 21 and 26): the blocking appointments
 * that schedule, staff and catalog changes check, and availability subtracts.
 */
@Component
@Transactional(readOnly = true)
class BookingBlockingAppointments implements BlockingAppointments {

	private final AppointmentRepository appointments;

	private final AppointmentRecorder recorder;

	private final Clock clock;

	BookingBlockingAppointments(AppointmentRepository appointments, AppointmentRecorder recorder, Clock clock) {
		this.appointments = appointments;
		this.recorder = recorder;
		this.clock = clock;
	}

	@Override
	public List<BlockingAppointment> atBranch(UUID salonId, UUID branchId, Instant endingAfter) {
		return of(appointments.findBySalonIdAndBranchIdAndStatusInAndBlockEndAtAfter(salonId, branchId,
				AppointmentStatus.BLOCKING, endingAfter));
	}

	@Override
	public List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId, Instant endingAfter) {
		return of(appointments.findBySalonIdAndStaffIdAndStatusInAndBlockEndAtAfter(salonId, staffId,
				AppointmentStatus.BLOCKING, endingAfter));
	}

	@Override
	public List<BlockingAppointment> withService(UUID salonId, UUID serviceId, Instant endingAfter) {
		return of(appointments.findWithService(salonId, serviceId, AppointmentStatus.BLOCKING, endingAfter));
	}

	@Override
	public List<BlockingAppointment> ofStaffBetween(UUID salonId, Collection<UUID> staffIds, Instant from,
			Instant to) {
		if (staffIds.isEmpty()) {
			return List.of();
		}
		return of(appointments.findBySalonIdAndStaffIdInAndStatusInAndBlockEndAtAfterAndStartAtBefore(salonId,
				staffIds, AppointmentStatus.BLOCKING, from, to));
	}

	/** Decision 38: keeps the status, flags the appointment, records it in the history. */
	@Override
	@Transactional
	public void flagForAttention(UUID salonId, Collection<UUID> appointmentIds, String reason, UUID actorUserId) {
		if (appointmentIds.isEmpty()) {
			return;
		}
		Instant now = clock.instant();
		for (Appointment appointment : appointments.findBySalonIdAndIdIn(salonId, appointmentIds)) {
			appointment.flagForAttention(reason, actorUserId, now);
			recorder.record(appointment, HistoryAction.ATTENTION_FLAGGED, appointment.getStatus(), actorUserId, reason);
		}
	}

	private static List<BlockingAppointment> of(List<Appointment> found) {
		return found.stream()
			.map(a -> new BlockingAppointment(a.getId(), a.getBranchId(), a.getStaffId(), a.serviceIds(), a.getStartAt(),
					a.getBlockEndAt()))
			.toList();
	}

}
