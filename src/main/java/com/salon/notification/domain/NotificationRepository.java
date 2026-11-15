package com.salon.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * User-scoped: notifications are loaded by {@code (id, userId)}, never by id alone.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	boolean existsBySourceIdAndUserIdAndKind(UUID sourceId, UUID userId, NotificationKind kind);

	Page<Notification> findByUserId(UUID userId, Pageable pageable);

	Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

	long countByUserIdAndReadAtIsNull(UUID userId);

	List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

	List<Notification> findBySourceId(UUID sourceId);

}
