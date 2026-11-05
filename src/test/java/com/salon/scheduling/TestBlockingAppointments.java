package com.salon.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for booking (Phase 4) in integration tests: blocking appointments registered per salon.
 * Honors the {@link BlockingAppointments} contract (only appointments ending after the given
 * moment). Tests use their own salons, so they do not see each other's appointments.
 */
public class TestBlockingAppointments implements BlockingAppointments {

	private final Map<UUID, List<BlockingAppointment>> bySalon = new ConcurrentHashMap<>();

	public BlockingAppointment add(UUID salonId, UUID branchId, UUID staffId, Instant start, Instant blockEnd) {
		BlockingAppointment appointment = new BlockingAppointment(UUID.randomUUID(), branchId, staffId, start,
				blockEnd);
		bySalon.computeIfAbsent(salonId, id -> new CopyOnWriteArrayList<>()).add(appointment);
		return appointment;
	}

	public void clear(UUID salonId) {
		bySalon.remove(salonId);
	}

	@Override
	public List<BlockingAppointment> atBranch(UUID salonId, UUID branchId, Instant endingAfter) {
		return bySalon.getOrDefault(salonId, List.of())
			.stream()
			.filter(a -> a.branchId().equals(branchId) && a.blockEnd().isAfter(endingAfter))
			.toList();
	}

	@Override
	public List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId, Instant endingAfter) {
		return bySalon.getOrDefault(salonId, List.of())
			.stream()
			.filter(a -> a.staffId().equals(staffId) && a.blockEnd().isAfter(endingAfter))
			.toList();
	}

}
