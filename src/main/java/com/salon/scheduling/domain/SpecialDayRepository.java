package com.salon.scheduling.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface SpecialDayRepository extends JpaRepository<SpecialDay, UUID> {

	Optional<SpecialDay> findBySalonIdAndBranchIdAndOnDate(UUID salonId, UUID branchId, LocalDate onDate);

	List<SpecialDay> findBySalonIdAndBranchIdAndOnDateBetweenOrderByOnDateAsc(UUID salonId, UUID branchId,
			LocalDate from, LocalDate to);

}
