# ADR-0003: Self-issued JWT access tokens with rotating refresh tokens

- Status: Accepted
- Date: 2026-11-02

## Context

Web and mobile clients are first-party. The options were: an external identity provider (Keycloak, Auth0, Cognito, Firebase Auth…), Spring Authorization Server (now part of Spring Security 7), or issuing tokens ourselves. Constraints: per-user cost at marketplace scale, KVKK cross-border transfer rules for hosted IdPs, Turkish phone-OTP flows, and a user model tied to tenant memberships.

## Decision

- Our own `/api/v1/auth/*` endpoints verify credentials and issue tokens with Spring Security's `JwtEncoder` (Nimbus), using an asymmetric key with a `kid` for rotation.
- Access tokens are short-lived (~15 min) JWTs. Claims: `sub` (user id), `iss`, `aud`, `exp`, `iat`, `jti`, platform roles. **Tenant memberships are not in the token.** They are loaded per request, so revocation takes effect immediately.
- Incoming tokens are validated with `spring-boot-starter-security-oauth2-resource-server`.
- Refresh tokens are opaque random values stored hashed in the database, rotated on every use, with reuse detection that revokes the whole token family.
- Web: access token in memory, refresh token in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to the refresh endpoint (CSRF-protected). Mobile: platform secure storage.
- Passwords: `DelegatingPasswordEncoder` (bcrypt by default, upgradable).
- Authorization: platform roles on the user; tenant-scoped roles on `StaffMember` mapped to permissions in code; own-vs-any resource rules.

## Consequences

- We own password reset, OTP, lockout and abuse protection.
- Because validation uses the standard resource-server path, moving to an external IdP later mostly changes configuration (`issuer-uri`), not API code.
- No OAuth2 flows for third parties until a real requirement appears (then revisit Spring Authorization Server).
