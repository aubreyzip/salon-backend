package com.salon.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token signing and lifetimes (ADR-0003).
 *
 * @param privateKey RSA private key, PKCS#8 PEM. Required unless {@code generateEphemeralKey} is set.
 * @param publicKey RSA public key, X.509 PEM. Required unless {@code generateEphemeralKey} is set.
 * @param keyId the {@code kid} header value, used for key rotation
 * @param generateEphemeralKey generate a throw-away key at startup; local development and tests only
 */
@ConfigurationProperties("salon.security.jwt")
public record JwtProperties(@DefaultValue("salon-backend") String issuer, @DefaultValue("salon-api") String audience,
		@DefaultValue("PT15M") Duration accessTokenTtl, @DefaultValue("P30D") Duration refreshTokenTtl,
		String privateKey, String publicKey, @DefaultValue("salon-1") String keyId,
		@DefaultValue("false") boolean generateEphemeralKey) {
}
