# salon-backend

Backend REST API for **Salon**, a multi-tenant salon/hairdresser appointment SaaS (initial market: Turkey). It serves separate web and mobile clients.

Architecture reasoning: `docs/architecture/initial-architecture-report.md`. Binding decisions: `docs/adr/`. Read the relevant ADR before changing anything it covers.

## Stack

- Java 25 (Temurin), Spring Boot 4.1.x, Maven (use the wrapper `./mvnw`)
- PostgreSQL 18, Flyway (plain SQL), Spring Data JPA / Hibernate 7
- Spring Security + OAuth2 Resource Server (self-issued JWTs, ADR-0003)
- Spring Modulith 2.x, springdoc-openapi 3.x
- Tests: JUnit 5, AssertJ, Mockito, Testcontainers 2 (PostgreSQL), spring-security-test

## Commands

- Build and all tests: `./mvnw verify` (needs Docker running for Testcontainers)
- Run locally: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (starts `compose.yaml` PostgreSQL automatically). The `local` profile enables a throw-away JWT key and the logging email/SMS placeholders: verification codes appear in the application log.
- Run with a throwaway Testcontainers database instead: run `TestSalonBackendApplication` from the IDE (also uses `local`)
- API docs while running: `/swagger-ui.html`, `/v3/api-docs`
- Windows: use `mvnw.cmd`. `JAVA_HOME` must point to JDK 25.
- Without the `local` profile, startup fails unless `salon.security.jwt.private-key` / `public-key` (PEM) are set. This is intentional.

## Module rules (ADR-0001)

- Base package `com.salon`. Each direct sub-package is a Modulith module (`com.salon.booking`, ...).
- Other modules may only use types in a module's **root package**. Sub-packages (`web`, `application`, `domain`, `infrastructure`) are internal.
- Reference other modules' data by ID only. No JPA associations across modules.
- Cross-module side effects go through domain events + `@ApplicationModuleListener`. Listeners must be idempotent.
- `ModularityTests` must stay green. Never weaken it to make a change pass.
- `shared` holds only small cross-cutting primitives (`BaseEntity`, `Ids`, `ApiException`, error handling, `Clock`). No business logic.
- Current modules: `shared`, `identity` (accounts, tokens, security config; public `UserDirectory`), `audit` (append-only `AuditLog`), `messaging` (email/SMS gateway ports; logging placeholders until providers are chosen), `salons` (package `com.salon.salons`: tenant salons, branches, staff memberships and roles, invitations, provinces; public `TenantAccess`, `StaffRole`, `SalonPermission`).
- Entities extend `BaseEntity` (UUIDv7 id assigned on construction, `@Version`). Inject `Clock` for "now".
- Business errors: throw `ApiException(status, CODE, detail)`. Services that must persist state before failing (failed logins, code attempts) use `@Transactional(noRollbackFor = ApiException.class)`.
- Interfaces (ports) only for external systems. No interface-per-service, no separate persistence model.

## API conventions

- All endpoints under `/api/v1`. Areas: `/public`, `/auth`, `/me`, `/salons/{salonId}`, `/admin`.
- Request/response DTOs are Java records. Never serialize JPA entities.
- Get the caller with `@CurrentUserId UUID userId` (from the access token), not by parsing the JWT yourself.
- Paged endpoints return `Page<T>`. It is serialized as `{"content": [...], "page": {...}}` (max page size 100).
- Errors are RFC 9457 `ProblemDetail` with a stable `code` field.
- Changes within v1 are additive only (mobile apps in the field cannot be updated instantly).
- Booking and payment-initiating POSTs require an `Idempotency-Key` header.

## Database rules (ADR-0004, ADR-0005, ADR-0006)

- The schema is owned by Flyway. `ddl-auto` stays `validate`.
- Migration names use timestamps: `V2026_11_02_1200__description.sql`. **Never edit a merged migration.** Add a new one instead.
- Every tenant-owned table has a non-null `salon_id`. Same-tenant references use composite foreign keys.
- Index every foreign key column. Prefer `CHECK`/`UNIQUE`/exclusion constraints over application-only checks.
- Moments: `Instant` / `timestamptz`. Local schedules: `LocalTime` + branch IANA `time_zone`. Money: `BigDecimal` / `numeric(12,2)` + currency.
- Use `Locale.ROOT` for case normalization (Turkish `İ/ı` problem).
- `spring.jpa.open-in-view` is false: load what you need inside the transaction.

## Security rules (ADR-0002, ADR-0003)

- Tenant access is checked through one component before any tenant use case: call `TenantAccess.require(salonId, userId, SalonPermission.X)` (or `requireMember`) first in every salon-scoped service method. Non-members get 404, members without permission 403.
- Check permissions, never role names. Role → permission mapping and branch scope live in `StaffRole` (ADR-0010).
- Pick the right `TenantAccess` check:
  - `require`: salon-level work (settings, branch setup, staff, catalog).
  - `requireInBranch`: branch-specific operational work (appointment books, schedules, clients).
  - `requireForStaffResource`: one staff member's schedule or appointments (`*_ANY` or own via `*_OWN`).
  - Filter lists with `SalonMembership.branchRestriction()`.
  - Branch assignments never grant permissions.
- Load tenant data by `(id, salonId)` (e.g. `findByIdAndSalonId`), never by id alone. Other tenants' resources return 404.
- New salon-scoped endpoints must be added to `TenantIsolationIntegrationTests`.
- Membership changes (staff create/update/deactivate/invite, invitation acceptance) first lock the salon row (`SalonRepository.findByIdForUpdate`), then check access, then `ensureModifiable()`. The lock prevents races such as two owners demoting each other at once; checking access before `ensureModifiable()` keeps a salon's suspension hidden from outsiders. Follow the same pattern for any future salon-wide invariant.
- Never log tokens, passwords, OTPs, or unmasked phone numbers or emails (KVKK).
- No secrets in the repository. Configuration comes from environment variables. `.env` is git-ignored.

## Messaging rules (ADR-0011)

- Email and SMS are currently sent synchronously inside the business transaction, through logging placeholders only. Never plug a real provider into that path: it would hold DB connections and the salon row lock during network calls, and could send messages for transactions that then roll back.
- Phase 5 moves delivery to an after-commit transactional outbox (Modulith event publication registry). Events carry ids only. Codes and tokens are minted at delivery time and stored only as hashes.

## Testing rules

- Use PostgreSQL via Testcontainers for anything touching the database. **Never H2.**
- Integration tests use `@IntegrationTest` (shared context and container, MockMvc, recording email/SMS gateways with `lastCodeSentTo(...)`). Use unique emails and phone numbers per test, since the database is shared.
- Drive the API with the `TestApi` bean: `signUp`, `makePlatformAdmin`, `createSalon`, `createBranch`, `addMember` (invite + accept), `get/post/put`.
- Race tests use `Concurrently.run(...)` and assert on the database state afterwards. Simulate email outages with `RecordingEmailGateway.failDeliveryTo(email)` (always restore it in `finally`).
- Check database constraints by name with direct SQL (see `SalonDatabaseConstraintsIntegrationTests`).
- Tests must not depend on execution order. Verify with `-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random -Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random`.
- Pure domain logic (especially the availability engine) gets plain unit tests without Spring.
- Every management endpoint gets tenant-isolation tests.
- Spring Boot 4: use `@MockitoBean` (not `@MockBean`); add `@AutoConfigureMockMvc` for MockMvc in `@SpringBootTest`.

## Do not add without an explicit decision

Lombok, H2, Liquibase, Redis, message brokers, Spring Cloud, Quartz, WebFlux/R2DBC, Spring Authorization Server, an external IdP, GraalVM native, MapStruct.

## Code style

- Follow the generated Initializr style: tabs for indentation in Java and XML.
- Constructor injection only; no field injection.
