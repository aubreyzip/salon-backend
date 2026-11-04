package com.salon.scheduling.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface BranchHoursRepository extends JpaRepository<BranchHours, UUID> {

	Optional<BranchHours> findBySalonIdAndBranchId(UUID salonId, UUID branchId);

}
