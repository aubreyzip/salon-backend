package com.salon.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, UUID> {

	Optional<ServiceOffering> findBySalonIdAndServiceIdAndBranchId(UUID salonId, UUID serviceId, UUID branchId);

	List<ServiceOffering> findBySalonId(UUID salonId);

	List<ServiceOffering> findBySalonIdAndServiceId(UUID salonId, UUID serviceId);

	List<ServiceOffering> findBySalonIdAndBranchId(UUID salonId, UUID branchId);

	List<ServiceOffering> findBySalonIdAndBranchIdAndServiceIdIn(UUID salonId, UUID branchId,
			Collection<UUID> serviceIds);

}
