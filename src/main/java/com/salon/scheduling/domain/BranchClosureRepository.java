package com.salon.scheduling.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface BranchClosureRepository extends JpaRepository<BranchClosure, UUID> {

	Optional<BranchClosure> findByIdAndSalonIdAndBranchId(UUID id, UUID salonId, UUID branchId);

	/** Closures that end on or after {@code from}. */
	List<BranchClosure> findBySalonIdAndBranchIdAndEndDateGreaterThanEqualOrderByStartDateAsc(UUID salonId,
			UUID branchId, LocalDate from);

	/** Closures with at least one day in {@code [from, to]}. */
	List<BranchClosure> findBySalonIdAndBranchIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(UUID salonId,
			UUID branchId, LocalDate to, LocalDate from);

}
