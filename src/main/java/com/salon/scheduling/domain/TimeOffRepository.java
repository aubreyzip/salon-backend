package com.salon.scheduling.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface TimeOffRepository extends JpaRepository<TimeOff, UUID> {

	Optional<TimeOff> findByIdAndSalonIdAndStaffId(UUID id, UUID salonId, UUID staffId);

	/** Time off overlapping {@code [from, to)}. */
	List<TimeOff> findBySalonIdAndStaffIdAndEndAtAfterAndStartAtBeforeOrderByStartAtAsc(UUID salonId, UUID staffId,
			Instant from, Instant to);

	/** Time off of several staff members overlapping {@code [from, to)}. */
	List<TimeOff> findBySalonIdAndStaffIdInAndEndAtAfterAndStartAtBefore(UUID salonId, Collection<UUID> staffIds,
			Instant from, Instant to);

}
