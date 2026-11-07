package com.salon;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.salon.salons.BlockingAppointment;
import com.salon.salons.BlockingAppointments;

/**
 * A second source of blocking appointments for integration tests, next to the booking module:
 * appointments at arbitrary times without setting up working hours, registered per salon. Honors
 * the {@link BlockingAppointments} contract and records override flags. Tests use their own salons,
 * so they do not see each other's appointments; call {@link #clear} after each test.
 */
public class TestBlockingAppointments implements BlockingAppointments {

	private final Map<UUID, List<BlockingAppointment>> bySalon = new ConcurrentHashMap<>();

	private final Map<UUID, String> flagged = new ConcurrentHashMap<>();

	public BlockingAppointment add(UUID salonId, UUID branchId, UUID staffId, Instant start, Instant blockEnd) {
		return add(salonId, branchId, staffId, Set.of(), start, blockEnd);
	}

	public BlockingAppointment add(UUID salonId, UUID branchId, UUID staffId, Set<UUID> serviceIds, Instant start,
			Instant blockEnd) {
		BlockingAppointment appointment = new BlockingAppointment(UUID.randomUUID(), branchId, staffId, serviceIds,
				start, blockEnd);
		bySalon.computeIfAbsent(salonId, id -> new CopyOnWriteArrayList<>()).add(appointment);
		return appointment;
	}

	public void clear(UUID salonId) {
		bySalon.getOrDefault(salonId, List.of()).forEach(appointment -> flagged.remove(appointment.id()));
		bySalon.remove(salonId);
	}

	/** The override reason the appointment was flagged with, or {@code null}. */
	public String flagReason(UUID appointmentId) {
		return flagged.get(appointmentId);
	}

	@Override
	public List<BlockingAppointment> atBranch(UUID salonId, UUID branchId, Instant endingAfter) {
		return stream(salonId).filter(a -> a.branchId().equals(branchId) && a.blockEnd().isAfter(endingAfter))
			.toList();
	}

	@Override
	public List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId, Instant endingAfter) {
		return stream(salonId).filter(a -> a.staffId().equals(staffId) && a.blockEnd().isAfter(endingAfter)).toList();
	}

	@Override
	public List<BlockingAppointment> withService(UUID salonId, UUID serviceId, Instant endingAfter) {
		return stream(salonId).filter(a -> a.serviceIds().contains(serviceId) && a.blockEnd().isAfter(endingAfter))
			.toList();
	}

	@Override
	public List<BlockingAppointment> ofStaffBetween(UUID salonId, Collection<UUID> staffIds, Instant from,
			Instant to) {
		return stream(salonId)
			.filter(a -> staffIds.contains(a.staffId()) && a.blockEnd().isAfter(from) && a.start().isBefore(to))
			.toList();
	}

	@Override
	public void flagForAttention(UUID salonId, Collection<UUID> appointmentIds, String reason, UUID actorUserId) {
		stream(salonId).filter(a -> appointmentIds.contains(a.id())).forEach(a -> flagged.put(a.id(), reason));
	}

	private Stream<BlockingAppointment> stream(UUID salonId) {
		return bySalon.getOrDefault(salonId, List.of()).stream();
	}

}
