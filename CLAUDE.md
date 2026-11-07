# salon-backend

Backend REST API for **Salon**, a multi-tenant salon/hairdresser appointment SaaS (initial market: Turkey). It serves separate web and mobile clients.

**Start here:** `docs/project-status.md` covers which phases are done, the next phase's scope, open product decisions and known limitations.

Binding decisions: `docs/adr/` (index: `docs/adr/README.md`). Read the relevant ADR before changing anything it covers. Original reasoning and roadmap: `docs/architecture/initial-architecture-report.md`; where it conflicts with an ADR or the code, the ADR or code wins (see the "Superseded" list at its top).

## Workflow

- Work phase by phase as in the roadmap (report §14). Do not start the next phase without the user's go-ahead.
- Never commit or push unless the user asks.
- Before reporting work as done, run `./mvnw clean verify` and a randomized-order run (see Testing rules). Report the real counts and failures.
- When behavior or a decision changes, update the affected ADR, `docs/project-status.md` and this file in the same change. Describe open product questions instead of inventing behavior.

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
- `shared` holds only small cross-cutting primitives: `BaseEntity`, `Ids`, `ApiException`, `CurrentUserId`, `SecureTokens`, `EmailAddresses`, `PhoneNumbers` (Turkish mobile to E.164, masking), error handling, `Clock`, paging config. No business logic.
- Current modules and their public (root-package) APIs:
  - `identity`: accounts, tokens, security config. Public: `UserDirectory`, `UserSummary`.
  - `audit`: `AuditLog`, `AuditRecord`.
  - `messaging`: `EmailGateway`, `SmsGateway` (logging placeholders until providers are chosen).
  - `salons` (package `com.salon.salons`): salons, branches, staff and roles, invitations, provinces. Public: `TenantAccess`, `SalonMembership`, `StaffRole`, `SalonPermission`, `PermissionScope`, `SalonDirectory` (salon, branch and staff facts plus `lockSalon`/`ensureModifiable` for other modules, `lockSalonShared` for bookings), `BranchInfo`, `StaffInfo`, extension point `BlockingAppointments` / `BlockingAppointment` (implemented by booking), `AppointmentConflicts` (the shared 409 and override), `ConflictOverride`.
  - `catalog`: service categories (platform taxonomy), sections, services, branch offerings, staff skills (ADR-0012). Public: `ServiceCatalog`, `BookingRequirements`.
  - `scheduling`: opening hours, special days, closures, working hours, time off, availability engine (ADR-0012). Public: `BookableSlots` (one exact start, customer or salon rules).
  - `booking`: appointments, salon clients, idempotency, lifecycle and expiry jobs (ADR-0013). Public: `AppointmentEvent` (domain events, ids only).
- Dependencies: `salons ← catalog ← scheduling ← booking`. Lower modules must not depend on higher ones.
- Entities extend `BaseEntity` (UUIDv7 id assigned on construction, `@Version`). Inject `Clock` for "now".
- Business errors: throw `ApiException(status, CODE, detail)`. Services that must persist state before failing (failed logins, code attempts) use `@Transactional(noRollbackFor = ApiException.class)`.
- Interfaces (ports) only for external systems. No interface-per-service, no separate persistence model. One exception: an extension interface in a module's root package that a *dependent* module implements, when an answer is needed synchronously and a direct call would be a cycle (ADR-0001; e.g. `salons.BlockingAppointments`). Inject all implementations; none means "nothing to report".
- Changes that affect future appointments must go through `salons.AppointmentConflicts.check` (ADR-0012 decisions 21, 25, 26). It returns `409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS`, listing only the appointments the caller may view, or applies the caller's `conflictOverride` (needs `APPOINTMENT_CONFLICT_OVERRIDE`) and flags them. It covers:
  - schedule changes that remove time, via `ScheduleConflicts`;
  - staff deactivation, branch unassignment, `bookable = false`, skill removal, service archiving and offering removal.
  - Never silently invalidate appointments. New such changes accept `@Valid ConflictOverride conflictOverride` in the body; bodiless ones take `ConflictOverride.Body` as an optional body.
- Booking (ADR-0013):
  - Lock order is the idempotency key, then the customer lock (`CustomerLocks`), then the salon row `FOR SHARE`. Never take them in another order.
  - Bookings and reschedules run through `BookingExecutor` (idempotency, deadlock retries, constraint translation). The exclusion constraints are the final arbiter.
  - Status changes are `Appointment` methods; every change is recorded with `AppointmentRecorder` (history + event).

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
- Migrations live in `src/main/resources/db/migration/<module>/`. Names use timestamps: `V2026_11_02_1200__description.sql`. **Never edit a merged migration.** Add a new one instead.
- Every tenant-owned table has a non-null `salon_id`. Same-tenant references use composite foreign keys.
- Index every foreign key column. Prefer `CHECK`/`UNIQUE`/exclusion constraints over application-only checks.
- Moments: `Instant` / `timestamptz`. Local schedules: `LocalTime` + branch IANA `time_zone`. Money: `BigDecimal` / `numeric(12,2)` + currency. Never set `hibernate.jdbc.time_zone`: it shifts `time` values by the JVM offset (ADR-0006).
- Use `Locale.ROOT` for case normalization (Turkish `İ/ı` problem).
- `spring.jpa.open-in-view` is false: load what you need inside the transaction.

## Security rules (ADR-0002, ADR-0003, ADR-0010)

- Every salon-scoped service method calls `TenantAccess` before touching tenant data. Non-members get 404 `SALON_NOT_FOUND`, members without permission 403 `INSUFFICIENT_PERMISSION`, branches outside the member's scope 403 `BRANCH_NOT_ASSIGNED`.
- Check permissions, never role names. Role → permission mapping and branch scope live in `StaffRole`.
- Pick the right `TenantAccess` check (`requireMember` is for member-wide reads):
  - `require`: salon-level work (settings, branch setup, staff, catalog).
  - `requireInBranch`: branch-specific operational work (appointment books, schedules, clients).
  - `requireForStaffResource`: one staff member's schedule or appointments in a branch (`*_ANY` or own via `*_OWN`).
  - `requireForStaffMember`: one staff member's resource without a branch, e.g. time off (`*_ANY` or own via `*_OWN`; branch-scoped members need a shared branch).
  - `requireAny`: salon-level work any one of several permissions allows (e.g. creating a walk-in client).
  - Appointments: reads per `APPOINTMENT_VIEW_ANY` (covered branches) or `_OWN`; not visible means `404 APPOINTMENT_NOT_FOUND`. Changes via `requireForStaffResource(APPOINTMENT_MANAGE_ANY, _OWN)`. Clients need `CLIENT_VIEW`, branch-scoped holders only see clients with appointments in their branches (ADR-0013 decision 36).
  - Filter lists with `SalonMembership.branchRestriction()`.
  - Branch assignments never grant permissions.
- Load tenant data by `(id, salonId)` (e.g. `findByIdAndSalonId`), never by id alone. Other tenants' resources return 404.
- New salon-scoped endpoints must be added to `TenantIsolationIntegrationTests`.
- Membership changes (staff create/update/deactivate/invite, invitation acceptance) first lock the salon row (`SalonRepository.findByIdForUpdate`), then check access, then `ensureModifiable()`. The lock prevents races such as two owners demoting each other at once; checking access before `ensureModifiable()` keeps a salon's suspension hidden from outsiders. Follow the same pattern for any future salon-wide invariant. Other modules use `SalonDirectory.lockSalon` / `ensureModifiable` (e.g. working hours: no overlap across branches).
- Every change to salon-scoped data in other modules calls `SalonDirectory.ensureModifiable` (suspended salons are read-only).
- Public (customer) endpoints load branches with `SalonDirectory.findPublicBranch`: only active branches of `ACTIVE` salons; everything else is `404 BRANCH_NOT_FOUND`.
- Never log tokens, passwords, OTPs, or unmasked phone numbers or emails (KVKK).
- No secrets in the repository. Configuration comes from environment variables. `.env` is git-ignored.

## Messaging rules (ADR-0011)

- Email and SMS are currently sent synchronously inside the business transaction, through logging placeholders only. Never plug a real provider into that path: it would hold DB connections and the salon row lock during network calls, and could send messages for transactions that then roll back.
- Phase 5 moves delivery to an after-commit transactional outbox (Modulith event publication registry). Events carry ids only. Codes and tokens are minted at delivery time and stored only as hashes.

## Testing rules

- Use PostgreSQL via Testcontainers for anything touching the database. **Never H2.**
- Integration tests use `@IntegrationTest` (shared context and container, MockMvc, recording email/SMS gateways with `lastCodeSentTo(...)`). Use unique emails and phone numbers per test, since the database is shared.
- Drive the API with the `TestApi` bean: `signUp(name)` / `signUp(name, email)`, `uniqueEmail()`, `makePlatformAdmin`, `createSalon`, `createBranch`, `approveSalon` (makes it `ACTIVE`/public), `createService`, `addMember` (invite + accept), `lastInvitationTokenSentTo`, `signUpCustomer` (verified phone), `verifyPhone`, `uniqueMobile()`, `get/post/put/delete/perform`, `postWithKey`/`performWithKey` (`Idempotency-Key`).
- Booking tests use the `BookableSalon` fixture (`com.salon`): an approved salon open 09:00–18:00, `service`, `staff`, `member`, `bookingPolicy`, `book`/`booked`, `walkIn`, `bookAtSalon`, `at(day, "10:00")`, `DAY` (three days ahead).
- `TenantIsolationIntegrationTests` resolves `{salon}`, `{branch}`, `{staff}`, `{service}`, `{section}`, `{offered}` (a service always offered at `{branch}`) and `{unknown}` (an id that exists nowhere) in paths and bodies. Endpoints that need an `Idempotency-Key` set the record's last flag. Matrix cases must be idempotent; deletes by id go in `deletesAreLimitedToMembersWithPermission`.
- `TestBlockingAppointments` (`com.salon`, in the shared integration context) is a second source of blocking appointments next to booking, for appointments at arbitrary times: `add(salonId, branchId, staffId, [serviceIds,] start, blockEnd)`, `flagReason(id)`, `clear(salonId)` (call it in `@AfterEach`).
- Booking jobs are disabled in tests (`salon.booking.jobs.enabled=false`); call `BookingJobs` directly. Check events with `@RecordApplicationEvents`.
- Availability tests: use dates a few days ahead (default policy: 60 minutes notice, 60 days advance). The engine itself is tested without Spring (`AvailabilityEngineTest`, including DST cases with `Europe/Berlin`).
- Race tests use `Concurrently.run(...)` and assert on the database state afterwards. Simulate email outages with `RecordingEmailGateway.failDeliveryTo(email)` (always restore it in `finally`).
- Check database constraints by name with direct SQL (see `SalonDatabaseConstraintsIntegrationTests`).
- Tests must not depend on execution order. Verify with `-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random -Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random`.
- Pure domain logic (especially the availability engine) gets plain unit tests without Spring.
- Spring Boot 4: use `@MockitoBean` (not `@MockBean`); add `@AutoConfigureMockMvc` for MockMvc in `@SpringBootTest`.

## Do not add without an explicit decision

Lombok, H2, Liquibase, Redis, message brokers, Spring Cloud, Quartz, WebFlux/R2DBC, Spring Authorization Server, an external IdP, GraalVM native, MapStruct.

## Code style

- Follow the generated Initializr style: tabs for indentation in Java and XML.
- Constructor injection only; no field injection.
