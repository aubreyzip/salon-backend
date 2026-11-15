package com.salon.notification.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

	boolean existsBySourceIdAndUserIdAndKind(UUID sourceId, UUID userId, NotificationKind kind);

	List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

	List<Notification> findBySourceId(UUID sourceId);

}
