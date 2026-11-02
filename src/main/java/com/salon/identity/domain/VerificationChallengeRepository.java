package com.salon.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationChallengeRepository extends JpaRepository<VerificationChallenge, UUID> {

	Optional<VerificationChallenge> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId,
			ChallengePurpose purpose);

	List<VerificationChallenge> findByUserIdAndPurposeAndConsumedAtIsNull(UUID userId, ChallengePurpose purpose);

	long countByUserIdAndPurposeAndCreatedAtAfter(UUID userId, ChallengePurpose purpose, Instant since);

	long countByTargetAndCreatedAtAfter(String target, Instant since);

}
