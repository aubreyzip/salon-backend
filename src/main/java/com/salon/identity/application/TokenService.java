package com.salon.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.identity.domain.PlatformRole;
import com.salon.identity.domain.RefreshToken;
import com.salon.identity.domain.RefreshTokenRepository;
import com.salon.identity.domain.RevokeReason;
import com.salon.identity.domain.UserAccount;
import com.salon.shared.Ids;
import com.salon.shared.SecureTokens;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues access tokens (signed JWTs) and refresh tokens (opaque, stored hashed).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class TokenService {

	static final String ROLES_CLAIM = "roles";

	private final JwtEncoder jwtEncoder;

	private final RefreshTokenRepository refreshTokens;

	private final JwtProperties properties;

	TokenService(JwtEncoder jwtEncoder, RefreshTokenRepository refreshTokens, JwtProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.refreshTokens = refreshTokens;
		this.properties = properties;
	}

	AuthTokens issue(UserAccount user, UUID familyId, Instant now) {
		Instant accessExpiresAt = now.plus(properties.accessTokenTtl());
		List<String> roles = user.getPlatformRoles().stream().map(PlatformRole::name).sorted().toList();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())
			.subject(user.getId().toString())
			.audience(List.of(properties.audience()))
			.issuedAt(now)
			.expiresAt(accessExpiresAt)
			.id(Ids.newId().toString())
			.claim(ROLES_CLAIM, roles)
			.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.keyId()).build();
		String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

		String refreshToken = SecureTokens.newUrlSafeToken();
		Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
		refreshTokens.save(new RefreshToken(user.getId(), familyId, SecureTokens.sha256(refreshToken), now,
				refreshExpiresAt));

		return new AuthTokens("Bearer", accessToken, accessExpiresAt, refreshToken, refreshExpiresAt);
	}

	void revokeFamily(UUID familyId, RevokeReason reason, Instant now) {
		refreshTokens.findByFamilyIdAndRevokedAtIsNull(familyId).forEach(token -> token.revoke(reason, now));
	}

	void revokeAllForUser(UUID userId, RevokeReason reason, Instant now) {
		refreshTokens.findByUserIdAndRevokedAtIsNull(userId).forEach(token -> token.revoke(reason, now));
	}

}
