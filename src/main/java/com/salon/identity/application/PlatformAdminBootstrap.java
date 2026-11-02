package com.salon.identity.application;

import java.time.Clock;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.identity.domain.PlatformRole;
import com.salon.identity.domain.UserAccountRepository;
import com.salon.shared.EmailAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Grants {@code PLATFORM_ADMIN} at startup to the registered user configured in
 * {@code salon.identity.bootstrap-platform-admin-email}. Register and verify that account first,
 * then restart with the property set.
 */
@Component
class PlatformAdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

	private final UserAccountRepository users;

	private final AuditLog auditLog;

	private final IdentityProperties properties;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	PlatformAdminBootstrap(UserAccountRepository users, AuditLog auditLog, IdentityProperties properties,
			TransactionTemplate transactionTemplate, Clock clock) {
		this.users = users;
		this.auditLog = auditLog;
		this.properties = properties;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		String email = properties.bootstrapPlatformAdminEmail();
		if (!StringUtils.hasText(email)) {
			return;
		}
		transactionTemplate.executeWithoutResult(status -> users.findByEmail(EmailAddresses.normalize(email))
			.filter(user -> user.isEmailVerified() && user.isActive())
			.ifPresentOrElse(user -> {
				if (user.grantPlatformRole(PlatformRole.PLATFORM_ADMIN, clock.instant())) {
					auditLog.record(AuditRecord.of(null, "identity.platform_role.granted", "user", user.getId()));
					log.info("Granted PLATFORM_ADMIN to the configured bootstrap user {}", user.getId());
				}
			}, () -> log.warn("Bootstrap platform admin email is set, but no active user with a verified email "
					+ "matches it yet. Register and verify that account, then restart.")));
	}

}
