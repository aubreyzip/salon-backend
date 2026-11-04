package com.salon.catalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface ServiceSectionRepository extends JpaRepository<ServiceSection, UUID> {

	Optional<ServiceSection> findByIdAndSalonId(UUID id, UUID salonId);

	List<ServiceSection> findBySalonIdOrderBySortOrderAscNameAsc(UUID salonId);

	boolean existsByIdAndSalonId(UUID id, UUID salonId);

}
