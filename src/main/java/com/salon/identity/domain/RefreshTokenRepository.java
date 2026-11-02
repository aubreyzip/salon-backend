package com.salon.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	List<RefreshToken> findByFamilyIdAndRevokedAtIsNull(UUID familyId);

	List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

}
