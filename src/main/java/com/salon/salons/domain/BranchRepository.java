package com.salon.salons.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface BranchRepository extends JpaRepository<Branch, UUID> {

	Optional<Branch> findByIdAndSalonId(UUID id, UUID salonId);

	List<Branch> findBySalonIdOrderByNameAsc(UUID salonId);

	List<Branch> findBySalonIdAndIdIn(UUID salonId, Collection<UUID> ids);

	boolean existsBySalonIdAndSlug(UUID salonId, String slug);

	boolean existsBySalonIdAndStatusAndLatitudeIsNotNull(UUID salonId, BranchStatus status);

}
