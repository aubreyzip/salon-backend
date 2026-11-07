package com.salon.booking.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

	Optional<IdempotencyRecord> findByUserIdAndKey(UUID userId, String key);

	@Modifying
	@Query("delete from IdempotencyRecord r where r.createdAt < :before")
	int deleteCreatedBefore(@Param("before") Instant before);

}
