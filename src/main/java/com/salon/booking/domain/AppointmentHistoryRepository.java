package com.salon.booking.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentHistoryRepository extends JpaRepository<AppointmentHistoryEntry, UUID> {

	List<AppointmentHistoryEntry> findByAppointmentIdOrderByOccurredAtAsc(UUID appointmentId);

}
