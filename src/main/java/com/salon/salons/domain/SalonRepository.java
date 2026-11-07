package com.salon.salons.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalonRepository extends JpaRepository<Salon, UUID> {

	/**
	 * Loads the salon with a row lock ({@code SELECT ... FOR UPDATE}) held until the transaction
	 * ends. Membership changes take this lock so that salon-wide invariants (e.g. "at least one
	 * active owner") are checked and changed by one transaction at a time.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Salon s where s.id = :id")
	Optional<Salon> findByIdForUpdate(@Param("id") UUID id);

	/**
	 * Locks the salon row in share mode ({@code FOR SHARE}) until the transaction ends. Bookings take
	 * it (ADR-0013): they do not block each other, but they wait for (and block) changes that take
	 * {@link #findByIdForUpdate}, so a booking cannot race a schedule change (ADR-0012, decision 21).
	 */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select s from Salon s where s.id = :id")
	Optional<Salon> findByIdForShare(@Param("id") UUID id);

	boolean existsBySlug(String slug);

	Page<Salon> findByStatus(SalonStatus status, Pageable pageable);

}
