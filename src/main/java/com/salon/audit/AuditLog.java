package com.salon.audit;

import java.time.Clock;

import com.salon.audit.domain.AuditLogEntry;
import com.salon.audit.domain.AuditLogEntryRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Append-only audit log. Entries are written in the caller's transaction, so they are committed
 * (or rolled back) together with the change they describe.
 */
@Service
public class AuditLog {

	private static final int MAX_USER_AGENT_LENGTH = 512;

	private final AuditLogEntryRepository repository;

	private final Clock clock;

	AuditLog(AuditLogEntryRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditRecord record) {
		HttpServletRequest request = currentRequest();
		String clientIp = request != null ? request.getRemoteAddr() : null;
		String userAgent = request != null ? truncate(request.getHeader("User-Agent")) : null;
		repository.save(new AuditLogEntry(clock.instant(), record.actorUserId(), record.salonId(), record.action(),
				record.entityType(), record.entityId(), record.details(), clientIp, userAgent));
	}

	private static HttpServletRequest currentRequest() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		return attributes instanceof ServletRequestAttributes servletAttributes ? servletAttributes.getRequest() : null;
	}

	private static String truncate(String value) {
		return value == null || value.length() <= MAX_USER_AGENT_LENGTH ? value
				: value.substring(0, MAX_USER_AGENT_LENGTH);
	}

}
