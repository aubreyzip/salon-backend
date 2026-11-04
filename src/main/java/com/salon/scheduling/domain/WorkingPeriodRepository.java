package com.salon.scheduling.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface WorkingPeriodRepository extends JpaRepository<WorkingPeriod, UUID> {

	List<WorkingPeriod> findBySalonIdAndStaffIdOrderByEffectiveFromAsc(UUID salonId, UUID staffId);

	List<WorkingPeriod> findBySalonIdAndBranchIdAndStaffIdIn(UUID salonId, UUID branchId, Collection<UUID> staffIds);

}
