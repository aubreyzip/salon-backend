package com.salon.identity.infrastructure.security;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.salon.identity.application.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * RS256 signing key, encoder (token issuance) and decoder (token validation) for access tokens.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

	private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

	@Bean
	RSAKey jwtSigningKey(JwtProperties properties) {
		if (StringUtils.hasText(properties.privateKey()) && StringUtils.hasText(properties.publicKey())) {
			RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(pem(properties.privateKey()));
			RSAPublicKey publicKey = RsaKeyConverters.x509().convert(pem(properties.publicKey()));
			return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(properties.keyId()).build();
		}
		if (!properties.generateEphemeralKey()) {
			throw new IllegalStateException("No JWT signing key configured. Set salon.security.jwt.private-key and "
					+ "salon.security.jwt.public-key (PEM), or set salon.security.jwt.generate-ephemeral-key=true "
					+ "for local development and tests.");
		}
		log.warn("Using an ephemeral JWT signing key: tokens become invalid on restart and are not shared between "
				+ "instances. Never use this in production.");
		try {
			return new RSAKeyGenerator(2048).keyID(properties.keyId()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Could not generate an ephemeral JWT signing key", ex);
		}
	}

	@Bean
	JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtSigningKey)));
	}

	@Bean
	JwtDecoder jwtDecoder(RSAKey jwtSigningKey, JwtProperties properties) {
		NimbusJwtDecoder decoder;
		try {
			decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey()).build();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Invalid JWT public key", ex);
		}
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(properties.issuer()),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
						audience -> audience != null && audience.contains(properties.audience()))));
		return decoder;
	}

	private static ByteArrayInputStream pem(String value) {
		return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
	}

}
