package com.salon.audit.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID> {

	List<AuditLogEntry> findByActorUserIdOrderByOccurredAtAsc(UUID actorUserId);

}
