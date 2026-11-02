package com.salon.salons.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salon.salons.StaffRole;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped: every lookup by staff id also takes the salon id (ADR-0002).
 */
public interface StaffMemberRepository extends JpaRepository<StaffMember, UUID> {

	Optional<StaffMember> findByIdAndSalonId(UUID id, UUID salonId);

	List<StaffMember> findBySalonIdOrderByDisplayNameAsc(UUID salonId);

	Optional<StaffMember> findBySalonIdAndUserIdAndStatus(UUID salonId, UUID userId, StaffStatus status);

	/** Any membership of the user in the salon, active or not (at most one, by a unique index). */
	Optional<StaffMember> findBySalonIdAndUserId(UUID salonId, UUID userId);

	List<StaffMember> findByUserIdAndStatus(UUID userId, StaffStatus status);

	long countBySalonIdAndRoleAndStatusAndUserIdIsNotNull(UUID salonId, StaffRole role, StaffStatus status);

}
