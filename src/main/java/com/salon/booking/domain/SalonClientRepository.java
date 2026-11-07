package com.salon.booking.domain;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalonClientRepository extends JpaRepository<SalonClient, UUID> {

	Optional<SalonClient> findByIdAndSalonId(UUID id, UUID salonId);

	Optional<SalonClient> findBySalonIdAndUserId(UUID salonId, UUID userId);

	Page<SalonClient> findBySalonId(UUID salonId, Pageable pageable);

	/** Clients with at least one appointment in one of the branches (ADR-0013 decision 36). */
	@Query("""
			select c from SalonClient c where c.salonId = :salonId and exists (
			  select 1 from Appointment a where a.salonId = :salonId and a.clientId = c.id and a.branchId in :branchIds)
			""")
	Page<SalonClient> findBySalonIdWithAppointmentsIn(@Param("salonId") UUID salonId,
			@Param("branchIds") Collection<UUID> branchIds, Pageable pageable);

	@Query("""
			select count(a) > 0 from Appointment a
			where a.salonId = :salonId and a.clientId = :clientId and a.branchId in :branchIds
			""")
	boolean hasAppointmentsIn(@Param("salonId") UUID salonId, @Param("clientId") UUID clientId,
			@Param("branchIds") Collection<UUID> branchIds);

}
