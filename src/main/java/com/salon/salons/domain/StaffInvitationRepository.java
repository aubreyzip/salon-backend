package com.salon.salons.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, UUID> {

	Optional<StaffInvitation> findByTokenHash(String tokenHash);

	List<StaffInvitation> findBySalonIdAndStaffIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID salonId, UUID staffId);

	List<StaffInvitation> findBySalonIdAndAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(UUID salonId,
			Instant now);

}
