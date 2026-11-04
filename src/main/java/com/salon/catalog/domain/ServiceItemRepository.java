package com.salon.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface ServiceItemRepository extends JpaRepository<ServiceItem, UUID> {

	Optional<ServiceItem> findByIdAndSalonId(UUID id, UUID salonId);

	List<ServiceItem> findBySalonIdOrderByNameAsc(UUID salonId);

	List<ServiceItem> findBySalonIdAndStatusOrderByNameAsc(UUID salonId, ServiceStatus status);

	List<ServiceItem> findBySalonIdAndIdIn(UUID salonId, Collection<UUID> ids);

	List<ServiceItem> findBySalonIdAndSectionId(UUID salonId, UUID sectionId);

}
