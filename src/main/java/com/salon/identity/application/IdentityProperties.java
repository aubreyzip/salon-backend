package com.salon.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login protection, one-time code rules and platform admin bootstrap.
 *
 * @param bootstrapPlatformAdminEmail if set, the registered user with this email is granted
 * {@code PLATFORM_ADMIN} at startup
 */
@ConfigurationProperties("salon.identity")
public record IdentityProperties(@DefaultValue("5") int maxFailedLogins,
		@DefaultValue("PT15M") Duration loginLockDuration, @DefaultValue("PT15M") Duration emailCodeTtl,
		@DefaultValue("PT5M") Duration smsCodeTtl, @DefaultValue("5") int maxCodeAttempts,
		@DefaultValue("PT1M") Duration codeResendInterval, @DefaultValue("5") int maxCodesPerHour,
		String bootstrapPlatformAdminEmail) {
}
