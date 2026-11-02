# Salon — Backend Architecture & Initialization Report

## Context

This is the initial architecture and initialization evaluation for `salon-backend`, written on 2026-11-02 when the repository was still empty. It chooses the technical foundation of a commercial, multi-tenant salon-booking SaaS for the Turkish market: Java, Spring Boot, PostgreSQL and REST, serving separate web and mobile clients. The business model is an annual salon subscription through iyzico, with no per-appointment commission.

The project was then generated with Spring Initializr (commit `9f3df55`), and the result was checked against this report (see "Generated Project Audit" and "Phase 0 Execution Plan" near the end). The short versions of the key decisions are the ADRs in `docs/adr/`. This document holds the full reasoning. Where it disagrees with code written later, the code and newer ADRs win.

**Labels used in this report**

| Label | Meaning |
|---|---|
| **[V]** | Verified on 2026-11-02 against a current source (listed at the end) |
| **[R]** | Architectural recommendation (my judgment) |
| **[A]** | Assumption (no information was given) |
| **[U]** | Not verified. Check it before relying on it |
| **[D]** | Product or business decision that needs your approval |

---

## 1. Executive Summary

**Overall recommendation [R].** Build a **modular monolith**: one deployable Spring Boot application and one PostgreSQL database. Module boundaries inside it are enforced by **Spring Modulith**, and modules communicate through in-process domain events backed by a transactional event-publication table. There are no microservices and no message broker. This keeps the system simple while the domain is still being discovered, and leaves a clean extraction path later.

**Main technology decisions**

| Area | Decision |
|---|---|
| Runtime | **Java 25 (LTS)**, Eclipse Temurin distribution [V: 25 is the current LTS; 27 is non-LTS; next LTS is 29 in Sep 2027] |
| Framework | **Spring Boot 4.1.2** (current stable default on start.spring.io) [V]. It brings Spring Framework 7.0.x, Spring Security 7.1, Hibernate 7.4 and Flyway 12.4 [V] |
| Build | **Maven**, with the Maven Wrapper |
| Database | **PostgreSQL 18**, managed in production [V: 18 is current and has a built-in `uuidv7()`] |
| Migrations | **Flyway**, plain SQL migrations |
| Multi-tenancy | Shared database and shared schema, with a `salon_id` tenant discriminator, composite foreign keys, and tenant checks enforced in services |
| Auth | Spring Security, plus **self-issued short-lived JWT access tokens** validated by OAuth2 Resource Server support, plus **opaque rotating refresh tokens** stored hashed in the database. No external IdP and no Authorization Server initially |
| Double booking | A **PostgreSQL exclusion constraint** (`btree_gist`, `tstzrange` overlap per employee) is the final guarantee. Application checks exist only to give friendly errors |
| API | REST under `/api/v1`, RFC 9457 Problem Details errors, OpenAPI 3 via springdoc 3.x (manual dependency) |
| Testing | JUnit/AssertJ/Mockito, Spring test slices, **Testcontainers PostgreSQL**, no H2, Spring Modulith structure verification |
| Integrations | Provider-neutral ports for payment (iyzico), email, SMS, push and object storage. Vendors are chosen per phase, behind adapters |

**Most important risks**

1. **Cross-tenant data leakage.** This is the most serious security risk in a multi-tenant SaaS. Mitigations are in §5 and §6.
2. **Underestimating scheduling.** Availability, policies and concurrency are the core product and need the most tests.
3. **Boot 4 ecosystem churn** (Jackson 3, Testcontainers 2, renamed starters). Some third-party libraries lag behind. Verify each library you add.
4. **Turkish compliance**: KVKK cross-border transfer rules, İYS for marketing messages, and e-invoicing (e-Fatura/e-Arşiv) for subscription billing. iyzico does not cover e-invoicing.
5. **Walk-in and phone bookings.** Salons will book customers who have no app account. If the model only lets registered users book, it will fail in real salons (§5).

---

## 2. Spring Initializr Configuration

| Field | Value | Notes |
|---|---|---|
| Project | **Maven** | Initializr defaults to *Gradle - Groovy* [V], so change it explicitly |
| Language | Java | |
| Spring Boot | **4.1.2** | The options shown are 4.1.2 (default) and 4.0.9 [V]. The 4.0 line's OSS support ends 2026-12-31. The 4.1 line's OSS support ends mid-2027 (sources disagree: June 30 vs July 31) [U]. Move to 4.2.x after its first patch release. 4.2 is not yet listed on Initializr [V] |
| Group | **`com.salonapp`** — placeholder **[D]** | Use the reverse of a domain the company actually owns. Renaming before code exists costs nothing; renaming later is painful |
| Artifact | `salon-backend` | Matches the repository name |
| Name | `salon-backend` | |
| Description | `Salon appointment and salon management platform – backend API` | |
| Package name | **`com.salonapp`** | Override the auto-derived `com.salonapp.salonbackend`. Spring Modulith treats each direct sub-package of the main class's package as a module, so a short root keeps module names clean (`com.salonapp.booking`, …) |
| Packaging | **Jar** | Runs with embedded Tomcat in a container. WAR is pointless here |
| Configuration | **YAML** | The metadata API does not expose this field [V]. If the web UI shows a Properties/YAML toggle, choose YAML. Otherwise rename `application.properties` → `application.yaml` after generation. YAML suits the nested config used here (datasource, security, integrations) |
| Java | **25** | Options shown: 27, 25, 21, 17. The Initializr default is 17 [V] |

### Why Java 25

| Option | Assessment |
|---|---|
| **25 (LTS)** | Current LTS (GA Sep 2025). Oracle Premier support runs to Sep 2030 [V]. Spring Boot 4 requires Java 17+ and "encourages the latest LTS" [V]. You get virtual threads without `synchronized` pinning (fixed in JDK 24), scoped values, compact object headers, and AOT-cache startup improvements |
| 21 (LTS) | Still viable; Temurin builds stay free. But Oracle JDK 21 updates after Sep 2026 move to the OTN license [V], and starting a new product on the older LTS only brings the upgrade forward |
| 27 | **Non-LTS**: GA Sep 2026, Premier support ends Mar 2027 [V]. It would force a JDK upgrade every six months. Rejected |

### Why Maven over Gradle [R]

| Criterion | Maven | Gradle |
|---|---|---|
| Learning curve and readability | Declarative and predictable. Any Java engineer can read a `pom.xml` | Flexible, but build scripts tend to turn into code |
| Dependency management | Spring Boot parent and BOMs work out of the box | Equivalent with the Boot plugin and platforms |
| Build speed | Fine for a single-module app | Faster with build cache and configuration cache. That matters for large multi-module builds, which this project is not |
| CI/CD | Universal support and simple caching of `~/.m2` | Also universal. The Gradle build cache is a real advantage at scale |
| Ecosystem examples | Most Spring, iyzico and library documentation shows Maven first | Good, but secondary |

For a single-deployable modular monolith built by a small team, Maven's predictability matters more than Gradle's speed. Modules are Java packages verified by Modulith, not build modules, so Gradle's main advantage, fast multi-project builds, does not apply. Revisit only if build time becomes a measured problem.

---

## 3. Dependency Selection

### Spring Boot 4 facts that affect coordinates [V]

- Starters were modularized. The MVC starter is **`spring-boot-starter-webmvc`**; `spring-boot-starter-web` is deprecated.
- Flyway now needs **`spring-boot-starter-flyway`**. A plain `flyway-core` dependency no longer triggers auto-configuration.
- The OAuth2 resource-server starter is now **`spring-boot-starter-security-oauth2-resource-server`**.
- Each technology has its own test starter (`spring-boot-starter-<tech>-test`). Each one brings `spring-boot-starter-test` transitively. `@WithMockUser` now requires `spring-boot-starter-security-test`.
- Jackson 3 is the default (package `tools.jackson`). `jackson-annotations` keeps the `com.fasterxml.jackson.annotation` package.
- Spring Authorization Server is now part of Spring Security.
- `@MockBean` is gone; use `@MockitoBean`. `@SpringBootTest` no longer configures MockMvc automatically; add `@AutoConfigureMockMvc`.
- Testcontainers 2 renamed its modules with a `testcontainers-` prefix (e.g. `org.testcontainers:testcontainers-postgresql`), and `PostgreSQLContainer` moved to `org.testcontainers.postgresql` [V, secondary sources]. The exact name of the JUnit artifact is **[U]**.

> After generating the project, **open the generated `pom.xml` and confirm the actual coordinates**. Initializr maps display names to starters per Boot version. The coordinates below come from the Boot 4 migration guide, not from a generated POM; I deliberately did not run Initializr.

### A. Essential (select at initialization)

| Initializr name (id) [V] | Coordinates (Boot 4) | Responsibility | Why this project needs it |
|---|---|---|---|
| **Spring Web** (`web`) | `spring-boot-starter-webmvc` (+ `spring-boot-starter-webmvc-test`) [U: confirm the mapping in the POM] | Servlet MVC, embedded Tomcat, Jackson 3 | REST API for web and mobile. A blocking MVC stack plus JDBC is the correct fit; WebFlux brings no benefit with a relational, transactional core |
| **Spring Security** (`security`) | `spring-boot-starter-security` (+ `spring-boot-starter-security-test`) | Filter chain, authentication, authorization, password encoding | Every non-public endpoint is protected, and method-level tenant checks are needed |
| **OAuth2 Resource Server** (`oauth2-resource-server`) | `spring-boot-starter-security-oauth2-resource-server` | Bearer JWT validation (Nimbus JOSE). It also provides `NimbusJwtEncoder`, which we use to *issue* tokens | Validates our own access tokens through the standard Spring path. A later move to an external IdP then changes configuration, not code. **jjwt is not needed** |
| **Spring Data JPA** (`data-jpa`) | `spring-boot-starter-data-jpa` (+ `spring-boot-starter-data-jpa-test`) | Hibernate 7.4 ORM, repositories, transactions | Rich, relational domain (salons, branches, staff, appointments) |
| **PostgreSQL Driver** (`postgresql`) | `org.postgresql:postgresql` (runtime) | JDBC driver | Our only database |
| **Validation** (`validation`) | `spring-boot-starter-validation` | Bean Validation via Hibernate Validator 9.1 | Validates request DTOs and configuration properties |
| **Flyway Migration** (`flyway`) | `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` [U: confirm Initializr adds the PG module; add it manually if it doesn't] | Versioned schema migrations | The schema is owned by migrations, never by Hibernate |
| **Spring Boot Actuator** (`actuator`) | `spring-boot-starter-actuator` | Health, liveness and readiness (enabled by default in Boot 4 [V]), metrics, info | Container orchestration and operations need these from day one |
| **Testcontainers** (`testcontainers`) | `spring-boot-testcontainers` + Testcontainers JUnit and PostgreSQL modules, test scope [U: confirm 2.x artifact names in the POM] | Real PostgreSQL in tests | Exclusion constraints, `tstzrange`, partial indexes and Flyway SQL cannot be tested on H2 |

### B. Strongly recommended (include at initialization)

| Name (id) | Coordinates | Responsibility | Justification |
|---|---|---|---|
| **Spring Modulith** (`modulith`) [V; supported range 4.0–4.3.0.M1] | `spring-modulith-starter-core`, `spring-modulith-starter-test`, BOM `spring-modulith-bom` (2.x) [U: exact artifacts as generated] | Verifies module boundaries, `@ApplicationModuleListener`, event publication registry, module tests, generated docs | Keeps module boundaries enforced by a test rather than by convention. Without it, a modular monolith drifts into a "big ball of mud." Add **`spring-modulith-starter-jpa`** (event publication registry) when the first async listener appears in Phase 4/5 [U: artifact name] |
| **Docker Compose Support** (`docker-compose`) | `spring-boot-docker-compose` (development only) | Starts `compose.yaml` services on `run` and wires the connection details | Lets any developer run the app with one command and no hand-written datasource config. Skipped in tests by default |
| **Spring Configuration Processor** (`configuration-processor`) | `spring-boot-configuration-processor` (optional/annotation processor) | IDE metadata for `@ConfigurationProperties` | We will have many typed configuration groups (`salon.security.jwt.*`, `salon.booking.*`, `salon.integrations.*`). Cost is near zero |
| *(manual)* **springdoc-openapi** | `org.springdoc:springdoc-openapi-starter-webmvc-ui` **3.0.x** [V: the 3.x line targets Boot 4/Framework 7; 3.0.3 was seen. Check Maven Central for the latest and whether it supports Boot 4.1] | OpenAPI 3 spec and Swagger UI | Two client teams (web and mobile) need a machine-readable contract and generated clients. **Correction after the audit:** Initializr *does* offer "SpringDoc OpenAPI" and generated version 3.2.0 |

### C. Optional / future (do not add until a concrete requirement exists)

| Name (id) | Add when | Notes |
|---|---|---|
| Java Mail Sender (`mail`) | Phase 5 (email) | Using SMTP keeps the email vendor swappable. Use Mailpit locally |
| HTTP Client (`spring-restclient`) [V] | The first outbound REST integration (iyzico if not using its SDK, SMS vendor) | `RestClient` / HTTP service interfaces |
| Prometheus (`prometheus`) **or** OTLP for metrics (`otlp-metrics`) | Once the observability backend is chosen (Phase 8) | Choose one, based on the hosting provider |
| Distributed Tracing (`distributed-tracing`) / OpenTelemetry (`opentelemetry`) | Phase 8 | Trace and span IDs in logs are useful even in a monolith |
| Spring Data Redis (`data-redis`), Spring Cache Abstraction (`cache`) | Measured need: distributed rate limiting or caching across several instances | Do not add Redis "just in case". It is another stateful system to run |
| CycloneDX SBOM support (`sbom-cyclone-dx`) | CI security hardening | Cheap supply-chain value |
| OAuth2 Client (`oauth2-client`) | Only for a server-side social-login redirect flow | Mobile Google/Apple sign-in is usually better done by verifying the provider's ID token server-side |
| Datasource Micrometer (`datasource-micrometer`) | When diagnosing query performance | |
| *(manual)* `com.iyzipay:iyzipay-java` [U: coordinates, version, transitive JSON/HTTP libraries] | Phase 7 (billing) | Or call the REST API directly with `RestClient`. Decide after reviewing the SDK |
| *(manual)* Bucket4j | Application-level rate limiting on auth/OTP endpoints | In-memory first; persistent backend later |
| *(manual)* ShedLock | More than one instance runs scheduled jobs | Or use `SELECT … FOR UPDATE SKIP LOCKED` job tables |
| *(manual)* AWS SDK v2 S3 | Phase 6 (media) | Works with any S3-compatible store (S3, R2, MinIO) |
| *(manual)* `libphonenumber` | Phase 1 if phone numbers are collected | Normalizes Turkish numbers to E.164 correctly |
| *(manual)* Firebase Admin SDK | Push notifications | Depends on the mobile stack (Expo Push is the alternative) |
| *(manual)* WireMock | Integration tests for external adapters | |

### Dependencies to avoid initially [R]

| Dependency | Reason |
|---|---|
| **Liquibase** | We choose Flyway (§5). Never install both |
| **OAuth2 Authorization Server** | A full OAuth2/OIDC server is for third-party clients and SSO. First-party apps with password/OTP login do not need it. Revisit when partners or integrations need delegated access |
| **Lombok** | Java 25 records cover DTOs. Lombok's `@Data`/`@EqualsAndHashCode` on JPA entities is a known source of bugs, and Lombok historically lags new JDK releases |
| **Spring Boot DevTools** | Little value with fast restarts. LiveReload is deprecated in 4.1 [V]. Harmless if you like it |
| **H2** | Would hide PostgreSQL-specific behavior that we deliberately rely on |
| **Spring REST Docs** | We chose springdoc. Don't run two documentation approaches |
| **RabbitMQ / Kafka** | No inter-service messaging is needed. The Modulith event registry gives at-least-once in-process delivery |
| **Quartz** | Appointment reminders and expiry fit a database job table plus `@Scheduled`. Quartz's clustered JDBC store is heavy |
| **Resilience4J** | Spring Framework 7 adds core resilience features (`@Retryable`, `@ConcurrencyLimit`) [U: confirm in the Framework 7 docs]. Add Resilience4J only for circuit breaking, when needed |
| **GraalVM Native** | Constrains reflection-heavy libraries (Hibernate, iyzico SDK) for a startup gain we don't need |
| **Spring Boot Admin, Spring Cloud** | No distributed system to manage |
| **WebFlux / R2DBC** | Wrong fit for a JPA and transaction-centric domain. Virtual threads cover concurrency needs |
| **MapStruct** | Add only if hand-written mapping becomes a real burden. Records plus small mapper methods are enough initially |

### Authentication concepts — these are not interchangeable

| Concept | What it is | Our use |
|---|---|---|
| **Spring Security** | The framework: filter chain, `SecurityContext`, authentication providers, authorization (URL and method), password encoders, CSRF/CORS | Always present |
| **Resource Server (JWT)** | Validates incoming bearer tokens (signature, `exp`, `iss`, `aud`) and converts claims to authorities. It **does not issue** tokens | Validates our access tokens |
| **Token issuance** | Something must mint tokens after verifying credentials | **Our own `/auth/*` endpoints**, using `JwtEncoder` with an asymmetric key (ES256 or RS256, with `kid` for rotation) |
| **Authorization Server** | A standards-compliant OAuth2/OIDC provider (authorization code + PKCE, client registrations, consent) | Not now |
| **External IdP** (Keycloak, Auth0, Cognito, Firebase Auth, Zitadel…) | Outsourced identity: login, MFA, social login, user store | Not now. Reasons: per-user cost at marketplace scale, KVKK cross-border transfer for hosted IdPs, Turkish phone-OTP flows, and coupling between the user store and our tenant model. Because we validate tokens through the standard resource-server path, migrating later mostly means changing `issuer-uri` |

---

## 4. Backend Architecture

### Style: pragmatic modular monolith [R]

- **Package by feature (module) at the top level.** Inside each module use **light layering** (`web`, `application`, `domain`, `infrastructure`).
- **Hexagonal ports only at real external boundaries**: payment gateway, SMS, email, push, object storage, maps, and AI later. Each port has one adapter plus a fake for tests and local development.
- **No repository-interface-over-Spring-Data abstraction and no separate persistence model.** JPA entities *are* the domain model, and Spring Data repositories are used directly by application services. Duplicating the model buys nothing here.
- **Comparison**: pure layered architecture (`controllers/`, `services/`, `repositories/`) puts the entire product in three folders and gives no boundaries. Full hexagonal architecture triples the class count with interfaces nobody swaps. Pure package-by-feature without enforcement erodes. The combination above, enforced by Modulith tests, keeps the benefits and drops the ceremony.

### Module map

| Module | Owns | Notes |
|---|---|---|
| `identity` | User accounts, credentials, email/phone verification, refresh tokens, password reset, platform roles | Knows nothing about tenants |
| `salon` | **Salon (tenant)**, **Branch**, branch settings and booking policies, **StaffMember** (membership, role and employee profile in one), staff↔branch assignment, invitations, salon onboarding and approval status | Merges "Salon", "Branch" and "Employee Management". Employees cannot exist outside a salon, and an employee *is* a tenant membership. Splitting them would create chatty cross-module calls with no benefit |
| `catalog` | Service categories, services, branch offerings, staff↔service skills, price/duration overrides | |
| `scheduling` | Staff working hours, time off, branch closures, **availability engine** (read-only computation) | Answers "which slots are free?" It never writes appointments |
| `booking` | Appointments, items, lifecycle, policies (cancel/reschedule), idempotency, history | The critical domain. Uses `scheduling` for availability and `catalog` for price snapshots |
| `customer` | Platform customer profile, favorites, **salon client records** (tenant-scoped CRM entries, optionally linked to a user) | Favorites live here; they are too small to be a module |
| `review` | Reviews, salon replies, moderation, rating aggregates | Separate because it has moderation workflow and its own aggregates; it reacts to `AppointmentCompleted` |
| `discovery` | Public search over branches (name, city/district, service, distance, rating) | **Documented exception**: a read-only query module that may use native SQL across other modules' tables (CQRS-lite). It never writes. Can later be backed by a denormalized table maintained through events |
| `billing` | Plans, subscriptions, payment records, iyzico adapter, webhook inbox, salon billing profile, entitlements | "Payment Integration" merges in here because billing is its only consumer. Future customer-payment features (deposits) would be a **separate** `payments` module, so billing stays decoupled from appointments |
| `notification` | Templates, delivery log, scheduled notifications (reminders), device tokens, preferences, channel ports (email/SMS/push) | Purely reactive to other modules' events |
| `media` | Upload sessions (presigned URLs), media metadata, ownership | |
| `audit` | Append-only audit log | Used by all modules through a tiny API or events |
| `shared` | Value types (`Money`, `TimeRange`, ID types), error model, pagination, security context helpers (`CurrentUser`, `TenantAccess`), web and config infrastructure | Keep it **small**. Business logic must not live here |

**Not modules [R]**
- **Platform administration.** Admin endpoints live in the module that owns the data (`/api/v1/admin/...`, guarded by platform roles). A separate admin module would have to reach into every other module.
- **Reporting/analytics.** Start with simple aggregate queries in `booking`. Real analytics later means a read replica or an export to a warehouse, not a module.
- **"Branch Management" / "Employee Management".** Both are folded into `salon`.

### Interaction rules [R]

1. **Synchronous calls** go only to another module's **public API**: beans and types in the module's root package or `@NamedInterface`. Internal packages are off-limits, and the Modulith verification test fails the build otherwise.
2. **Cross-module references use IDs only** (a UUID column). There are no JPA `@ManyToOne` relations across modules. Database foreign keys across modules are still allowed for integrity, since there is one database.
3. **Side effects go through domain events.** Examples: `AppointmentBooked` / `Confirmed` / `Cancelled` / `Completed`, `SalonActivated`, `SubscriptionActivated` / `Lapsed`. They are published inside the transaction and consumed by `@ApplicationModuleListener` after commit. The Modulith **event publication registry** persists them, so a crash after commit does not lose a notification (at-least-once delivery; listeners must be idempotent).
4. **Dependency direction**: `booking → scheduling → catalog → salon → identity`. `notification`, `review`, `audit` and `discovery` only consume. `billing` → `salon` happens via events only. There must be no cycles; Modulith detects them.

---

## 5. Database Architecture

### Initial domain model (conceptual, not code)

**identity**
- `user_account`: id, email (normalized), email_verified_at, phone_e164, phone_verified_at, password_hash, status, preferred_locale, created_at/updated_at
- `user_platform_role`: user_id, role (`PLATFORM_ADMIN`, `PLATFORM_SUPPORT`)
- `refresh_token`: id, user_id, family_id, token_hash, issued_at, expires_at, revoked_at, replaced_by_id, device/user-agent label
- `verification_challenge`: id, target (email or phone), channel, purpose (`SIGNUP`, `LOGIN_OTP`, `PASSWORD_RESET`, `PHONE_VERIFY`), code_hash, attempts, expires_at, consumed_at

**salon** (tenant = `salon`)
- `salon`: id, name, slug, status (`DRAFT` → `PENDING_REVIEW` → `ACTIVE` / `SUSPENDED` / `CLOSED`), created_by_user_id
- `branch`: id, salon_id, name, slug, structured address (city_id, district_id, neighbourhood, line), latitude/longitude, **time_zone (IANA)**, phone, status, **booking policy** (auto_confirm, slot_interval_minutes, min_notice_minutes, max_advance_days, cancellation_cutoff_minutes, reschedule_cutoff_minutes)
- `branch_opening_hours`: branch_id, day_of_week, opens_at, closes_at (local times, several rows per day allowed)
- `staff_member`: id, salon_id, **user_id (nullable)**, display_name, title, bio, photo_media_id, **role** (`OWNER`, `MANAGER`, `RECEPTIONIST`, `STAFF`), status, **bookable** (bool)
- `staff_branch`: staff_id, branch_id (many-to-many: a stylist may work at two branches on different days)
- `staff_invitation`: id, salon_id, staff_id, email/phone, token_hash, expires_at, accepted_at
- Reference data (`shared`): `city` (81 il), `district` (ilçe), seeded by migration

**catalog**
- `service_category`: id, salon_id, name, sort_order
- `service`: id, salon_id, category_id, name, description, default_duration_min, default_price, currency, buffer_after_min, status
- `branch_service`: branch_id, service_id, price_override, duration_override, active
- `staff_service`: staff_id, service_id, price_override, duration_override
- **Effective price and duration**: staff override → branch override → service default. Resolved in one place in `catalog`.

**scheduling**
- `staff_working_hours`: id, salon_id, staff_id, branch_id, day_of_week, start_time, end_time, effective_from, effective_to. Split shifts give implicit breaks; the effective range versions schedule changes without rewriting history.
- `staff_time_off`: id, salon_id, staff_id, starts_at, ends_at (timestamptz), type (`VACATION`, `SICK`, `BREAK`, `BLOCKED`), note
- `branch_closure`: id, salon_id, branch_id, starts_at, ends_at, reason (public holidays, renovation)

**customer**
- `customer_profile`: user_id, display name, notification preferences
- `favorite`: user_id, branch_id (unique pair)
- `salon_client`: id, salon_id, **user_id (nullable)**, name, phone_e164, notes, created_by. A salon's own record of a client, which covers phone-in and walk-in bookings for people without an account. When a user books online, the system finds or creates their `salon_client` in that salon.

**booking**
- `appointment`: id, salon_id, branch_id, staff_id, client_id (→ `salon_client`), booked_by_user_id, status, **start_at, end_at, block_end_at** (end plus buffer), total_price, currency, source (`CUSTOMER_APP`, `CUSTOMER_WEB`, `SALON`), customer_note, cancel_reason, cancelled_by, `version` (optimistic lock), timestamps
- `appointment_item`: appointment_id, service_id, **snapshot** of service name, duration and price, sort_order. Multi-service bookings (e.g. haircut + beard) are very common.
- `appointment_event`: appointment_id, from_status, to_status, actor_user_id, actor_type, occurred_at, reason. This is the lifecycle history.
- `idempotency_record`: user_id, idempotency_key, request_hash, response_status, response_body, created_at, with a unique key on (user_id, key)

**review**: `review` (id, salon_id, branch_id, **appointment_id unique**, author_user_id, staff_id, rating 1–5, comment, status, reply, reply_at) and `branch_rating_summary` (branch_id, avg, count)

**billing**: `plan` (code, name, interval, price, currency, trial_days, limits such as max_branches/max_staff, provider_plan_ref), `subscription` (salon_id, plan_id, status `TRIALING`/`ACTIVE`/`PAST_DUE`/`CANCELED`/`EXPIRED`, current_period_start/end, provider, provider_subscription_ref, provider_customer_ref), `payment_record`, `webhook_inbox` (provider, event_type, provider_event_id unique, payload jsonb, received_at, processed_at, error), `billing_profile` (legal name, tax office, tax number, address — needed for e-invoices)

**notification**: `notification` (recipient, channel, template_code, payload jsonb, status, scheduled_for, sent_at, attempts, provider_message_id, error), `device_token`, `notification_preference`

**media**: `media_asset` (id, salon_id nullable, owner_user_id, storage_key, purpose, content_type, size, width, height, status `PENDING_UPLOAD`/`READY`/`DELETED`)

**audit**: `audit_log` (id, occurred_at, actor_user_id, actor_type, salon_id, action, entity_type, entity_id, changes jsonb (minimal, no secrets), request_id, ip, user_agent). Append-only.

**Modulith**: the `event_publication` table, created by Flyway, not by auto-initialization.

### Key relationships

- User 1–N StaffMember (one person can be staff at several salons, or owner of one and stylist at another). User 1–1 CustomerProfile. User N–M PlatformRole.
- Salon 1–N Branch, 1–N StaffMember, 1–N Service, 1–1 active Subscription.
- StaffMember N–M Branch, N–M Service (with overrides).
- Appointment N–1 Branch, Staff and SalonClient; 1–N AppointmentItem.

### Multi-tenancy strategy [R]

| Option | Verdict |
|---|---|
| Database per tenant | Rejected. Thousands of small salons, plus customers who cross tenants by design |
| Schema per tenant | Rejected. Migration fan-out to thousands of schemas and connection-pool complications. Public discovery and a customer's cross-salon appointment list need cross-tenant queries |
| **Shared schema + `salon_id`** | **Chosen.** This is a *marketplace*: customer-facing reads are legitimately cross-tenant, while management operations are strictly tenant-scoped |

Enforcement uses several layers:
1. **Every tenant-owned table carries `salon_id`**, including child tables, so tenant filtering never depends on joins.
2. **Composite foreign keys enforce same-tenant references in the database.** For example, `appointment(salon_id, staff_id)` → `staff_member(salon_id, id)` and `appointment(salon_id, branch_id)` → `branch(salon_id, id)`. The database then makes "salon A's appointment with salon B's stylist" impossible. This is cheap and strong.
3. **The tenant is explicit in the API path**: `/api/v1/salons/{salonId}/...`. A single authorization component checks the caller's membership and permission for that `salonId` before any use case runs. Repository methods for tenant data always take `salonId`.
4. **Automated tenant-isolation tests** run for every management endpoint (§8).
5. **Future defense in depth: PostgreSQL Row-Level Security** using `SET LOCAL app.salon_id` per transaction. It is deferred because it complicates pooling, migrations and cross-tenant reads. Points 1–2 keep it possible to add later.

### Migration strategy (Flyway) [R]

**Flyway vs Liquibase.** We rely on PostgreSQL-specific DDL: exclusion constraints, `btree_gist`, partial and expression indexes, `tstzrange`, generated columns. Flyway's plain SQL expresses these directly. Liquibase's strengths are database-agnostic changelogs, diffing, and rollback tags (some of them paid). We need none of these with a single database and a forward-fix policy. Flyway Community is enough because we do not need undo migrations.

Rules:
- `spring.jpa.hibernate.ddl-auto=validate` in every environment. Hibernate never alters the schema.
- Name migrations by timestamp (`V2026_11_02_1200__create_identity.sql`) to avoid version collisions between parallel branches. Group them per module in `db/migration/<module>/`, which works because Flyway scans locations recursively [U: confirm recursive scanning in Flyway 12 docs].
- **A merged migration is never edited.** Fix mistakes with a new migration.
- Use **expand/contract** for zero-downtime changes: add a nullable column, backfill, switch code, then add the constraint or drop the old column.
- **Seed data**: reference data (cities/districts, plans, permission definitions if stored) goes in versioned migrations. **Development sample data** lives in a separate location (`db/dev-data`) enabled only by the `local` profile. Tests build their own fixtures and never rely on seed data.
- Production: Flyway runs at startup while there is one instance. Flyway takes a PostgreSQL advisory lock, so concurrent starts are safe. Later, migrations become a separate CI/CD step that runs before rollout.

### PostgreSQL conventions [R]

- **Primary keys: UUIDv7.** Time-ordered values give B-tree locality. IDs cannot be enumerated in URLs, which limits IDOR enumeration, and they can be generated before insert. Generate them in the application through Hibernate's UUID generator, which may offer a v7 style [U], or with PG18's `uuidv7()` [V]. Trade-off: the creation time is embedded in the ID, which is acceptable.
- **Time**: `timestamptz` for instants, `date`/`time` plus IANA `time_zone` for local schedules (see §7).
- **Money**: `numeric(12,2)` plus `currency char(3)`. Never floating point.
- **Text**: store emails normalized with `Locale.ROOT` lowercasing; beware the **Turkish dotted/dotless i** (`"I".toLowerCase()` under `tr-TR` gives `ı`). Name search can use `pg_trgm` + `unaccent` (later).
- **Constraints**: `NOT NULL` by default; `CHECK` constraints (end > start, price ≥ 0, rating 1–5); unique constraints (normalized email, `(user_id, branch_id)` favorites, `appointment_id` per review); partial unique indexes such as "one active subscription per salon".
- **Indexes**: index **every foreign key column** (PostgreSQL does not do this automatically); `appointment(staff_id, start_at)`, `appointment(branch_id, start_at)`, `appointment(client_id, start_at desc)`; GiST index from the exclusion constraint; `branch(city_id, district_id)`, plus lat/lng for bounding-box search.
- **No global soft delete.** Use explicit `status` fields (`ARCHIVED`, `INACTIVE`) because history (appointments) must keep referencing archived services and staff. KVKK erasure = anonymization (§11).
- **Transactions**: one transaction per use case, defined in the application service. `READ COMMITTED` (the default) plus database constraints plus optimistic locking (`@Version`) is enough. **`spring.jpa.open-in-view=false`** keeps transactions and lazy-loading out of the web layer.
- **Connection pooling**: HikariCP (default). Start with roughly 10 connections per instance, and keep the sum across instances below the database's `max_connections` minus a headroom. Add PgBouncer only if instance count grows. If virtual threads are enabled later, the pool still bounds database concurrency.
- **Extensions**: `btree_gist` (required for the exclusion constraint) now; `pg_trgm`, `unaccent` later; PostGIS only if distance search outgrows bounding-box + haversine. Confirm the managed provider supports each extension [U].

### Environment separation

| Environment | Database |
|---|---|
| local | `postgres:18` via Docker Compose; disposable |
| test | Testcontainers, a fresh container per test run (singleton or reusable locally for speed) |
| staging | Separate managed instance and credentials; synthetic data only |
| production | Managed PostgreSQL with point-in-time recovery, encryption at rest, private network only |

Production data is **never** copied to lower environments without anonymization (KVKK).

---

## 6. Security Architecture

### Authentication [R]

- **Customer login identifier [D]**: email + password, with a **phone number that must be verified by SMS OTP before the first booking** (this reduces fake bookings and no-shows; salons need the number anyway). Phone-only OTP login can be added later, which is popular in Turkey, but each login then costs an SMS and is exposed to SMS-pumping fraud.
- **Passwords**: `DelegatingPasswordEncoder` with the default **bcrypt** (strength ~12). It needs no extra dependency, and the `{id}` prefix allows migrating to Argon2id later; Spring's Argon2 encoder needs BouncyCastle. Enforce a minimum length and allow long passphrases; optionally check against breached-password lists later.
- **Access token**: a JWT signed with an asymmetric key, valid for **~15 minutes**. Claims: `sub` (user id), `iss`, `aud`, `exp`, `iat`, `jti`, platform roles. **Tenant memberships are not put in the token**: they change, and a stale token must not keep access to a salon that removed a staff member. Memberships are loaded per request (cheap indexed query, short-lived cache later).
- **Refresh token**: opaque random value (≥256 bits), **stored as a hash**, valid 30–60 days, **rotated on every use**, with **reuse detection** (presenting an already-rotated token revokes the whole family). Logout revokes the family. "Log out of all devices" revokes all of the user's tokens.
- **Client storage**: mobile uses Keychain/Keystore. Web keeps the access token in memory and the refresh token in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth/refresh`. That endpoint then needs CSRF protection (SameSite plus a required custom header). The same API serves both: the refresh endpoint accepts the token from the cookie or the body.
- **Signing keys**: loaded from the secret manager, with `kid` to allow rotation. A JWKS endpoint is optional (only our own app validates).
- **Abuse protection**: generic error messages on login, registration and password reset (no account enumeration); progressive delay and lockout per account and per IP; OTP attempt counters and expiry stored in the database.

### Authorization model [R]

This model uses no single global role enum. It has three layers:

1. **Platform roles** on the user (`PLATFORM_ADMIN`, `PLATFORM_SUPPORT`) grant access to `/api/v1/admin/**`.
2. **Tenant-scoped roles**: a `StaffMember.role` per salon (`OWNER`, `MANAGER`, `RECEPTIONIST`, `STAFF`). Each role maps to a set of **permissions** defined in code, for example `SALON_SETTINGS_MANAGE`, `BRANCH_MANAGE`, `STAFF_MANAGE`, `CATALOG_MANAGE`, `SCHEDULE_MANAGE_ANY`, `SCHEDULE_MANAGE_OWN`, `APPOINTMENT_VIEW_ANY`, `APPOINTMENT_VIEW_OWN`, `APPOINTMENT_MANAGE_ANY`, `APPOINTMENT_MANAGE_OWN`, `CLIENT_VIEW`, `BILLING_VIEW`, `BILLING_MANAGE`. The role-to-permission mapping lives in code first. Per-salon customizable roles can be added later as database tables without changing the permission checks.
3. **Resource-level rules**: "own" vs "any" (a stylist with `APPOINTMENT_MANAGE_OWN` may only manage appointments where `staff_id` is themselves). Optional branch restriction: a manager limited to certain branches.

Customer actions are not a tenant role. Any authenticated user can act as a customer, scoped to their own resources (`/api/v1/me/**`).

**Enforcement**: URL rules split by API area (`/public`, `/auth`, `/me`, `/salons/{salonId}`, `/admin`). Within an area, a single `TenantAccess` component is called from application services or `@PreAuthorize` (e.g. a check of `salonId` against a permission). Authorization checks happen **before** loading data where possible, and queries are always filtered by `salonId`. **Respond with 404 rather than 403** for resources in another tenant to avoid confirming that they exist.

### API security [R]

- **Input validation**: Bean Validation on request DTOs (sizes, formats, enums), domain invariants in services and entities, limits on body size and page size.
- **Errors**: RFC 9457 `ProblemDetail` with a stable machine-readable `code` and a `traceId`. Never expose stack traces or SQL errors.
- **CORS**: explicit allow-list of web origins per environment through configuration. Mobile apps do not need CORS. Never use `*` with credentials.
- **CSRF**: disabled for bearer-token endpoints and enabled or compensated for the cookie-based refresh endpoint (above).
- **Rate limiting**: coarse limits at the edge (cloud load balancer, WAF or Cloudflare) from launch. Fine-grained limits in the application for login, OTP send, registration, password reset and booking creation. OTP and attempt counters are stored in the database, so they are correct across instances. Other limits may be in memory until there are several instances.
- **Transport**: HTTPS only; HSTS at the edge.
- **Secrets**: never in git. Environment variables are injected from the cloud secret manager. `.env` is git-ignored locally. Use separate credentials per environment, and give the database user only the privileges it needs (no superuser; a migration role separate from the runtime role later).
- **Employee data access [D]**: by default staff see only their own appointments' client name and phone; `CLIENT_VIEW` grants the full client list.

### Audit trails [R]

- `audit_log` records security and business-sensitive actions: logins and failures, role changes, staff invitations, policy changes, appointment status changes made by the salon, subscription changes, admin actions and data exports. Written **in the same transaction** as the change.
- `appointment_event` records the domain history of each appointment for both customers and salons.
- Hibernate Envers is not used: field-level change tracking is noisy and misses intent.

---

## 7. Appointment Scheduling Architecture

### Time representation [R]

| Concept | Java | PostgreSQL | API |
|---|---|---|---|
| A moment (appointment start, time off, created_at) | `Instant` (or `OffsetDateTime` at the edges) | `timestamptz` | ISO-8601 with offset, e.g. `2026-11-05T14:30:00+03:00` |
| A weekly working pattern | `DayOfWeek` + `LocalTime` | `smallint` + `time` | `"09:00"` |
| A calendar day the user picks | `LocalDate` | `date` | `"2026-11-05"`, interpreted in the **branch's** time zone |
| A branch's zone | `ZoneId` | `text` (IANA, `Europe/Istanbul`) | Returned with branch details |

- The JVM, containers and JDBC session run in **UTC** (`hibernate.jdbc.time_zone=UTC`). There are no `LocalDateTime` instants anywhere.
- All local-to-instant conversion goes through `ZoneId`. Turkey has been fixed at UTC+3 since 2016, but the code must not assume that: DST gaps and overlaps for future markets are handled by `ZonedDateTime` rules plus tests with a DST zone.
- The branch's zone is the source of truth for schedules, not the customer's device zone.

### Availability calculation [R]

Availability is computed on demand. There is **no precomputed slot table**: such tables go stale, grow large, and need synchronization. For one staff member and day the input is small (a few working intervals, time-off rows and appointments).

For each query `(branch, service(s), date range, staff | any)`:
1. Resolve the effective duration (sum of items) and buffer from `catalog` for each candidate staff member, and keep only staff who are assigned to the branch, bookable, and able to perform all requested services.
2. Build the working intervals: `staff_working_hours` (effective on that date) ∩ `branch_opening_hours`, converted to instants in the branch's zone.
3. Subtract `staff_time_off`, `branch_closure`, and existing **blocking** appointments (`PENDING`, `CONFIRMED`) using `[start_at, block_end_at)`.
4. Generate candidate starts on the branch's `slot_interval_minutes` grid where `duration + buffer` fits.
5. Apply policy: `min_notice_minutes` (no booking 10 minutes from now) and `max_advance_days`.
6. "Any staff": take the union and assign a staff member at booking time (least-loaded or first-available). This is phase 2 of the UI, but the engine supports it from the start.

The engine is a **pure function over loaded data** in `scheduling`, which allows exhaustive unit and property tests. Caching per staff member and day with event-driven invalidation is a later optimization, added only if profiling demands it.

### Booking flow [R]

```
Client → POST /api/v1/branches/{branchId}/appointments   (Idempotency-Key header required)
  1. Check idempotency: same (user, key) → return the stored response. Same key with a different body → 422
  2. Validate: services offered at the branch, staff can perform them, salon bookable (subscription entitlement + ACTIVE)
  3. Compute price/duration snapshot; block_end_at = end_at + buffer
  4. Re-check availability (gives a friendly error and catches policy violations)
  5. INSERT appointment (+ items, event, idempotency record) in ONE transaction
       → the exclusion constraint is the final arbiter
       → on violation (SQLSTATE 23P01) → 409 Problem {code: "SLOT_UNAVAILABLE"}
  6. Status = CONFIRMED if branch.auto_confirm, else PENDING (with confirmation deadline)
  7. Publish AppointmentBooked → after commit: notifications, salon client upsert, audit
```

### Double-booking guarantee [R]

The database enforces it, so it holds regardless of application instances, threads or bugs:

```
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE appointment ADD CONSTRAINT appointment_no_staff_overlap
  EXCLUDE USING gist (staff_id WITH =, tstzrange(start_at, block_end_at, '[)') WITH &&)
  WHERE (status IN ('PENDING', 'CONFIRMED'));
```
*(Illustrative DDL only, not a migration file.)*

- Two concurrent inserts for overlapping ranges of the same staff member: PostgreSQL makes the second wait for the first and then fails it. Exactly one succeeds, at `READ COMMITTED`, with no advisory locks, `SELECT … FOR UPDATE` or `SERIALIZABLE` needed.
- The half-open `[)` range lets back-to-back appointments touch without conflicting.
- Cancelled, rejected, completed and no-show rows drop out of the constraint through the `WHERE` clause.
- The expression must be immutable. The range constructor over two `timestamptz` columns should be [U]. **A migration test on Testcontainers must prove this** (Phase 4 acceptance criterion). The fallback is a plain stored `tstzrange` column maintained by the application.
- Future resource constraints (chairs, rooms) become additional exclusion constraints on the corresponding tables.

### Lifecycle

```
PENDING ──confirm──► CONFIRMED ──complete──► COMPLETED
   │  └─reject──► REJECTED          │ └─no-show──► NO_SHOW
   └─expire──► EXPIRED              └─cancel──► CANCELLED (by CUSTOMER | SALON)
reschedule: CONFIRMED/PENDING → same row, new times (constraint re-checked), event recorded
```
- Transitions are methods on the aggregate that enforce allowed moves. Every transition writes `appointment_event`.
- Optimistic locking (`@Version`) resolves races such as a salon confirming while the customer cancels: one wins and the other gets 409.
- Policies are enforced server-side: customer cancel/reschedule only before `cancellation_cutoff_minutes`, while the salon may override with an audit entry.
- A scheduled job expires unconfirmed `PENDING` appointments and marks past `CONFIRMED` ones as completable. Run it with a database job table and `SKIP LOCKED`, or ShedLock once there are several instances.
- Rescheduling is an atomic update of the same row in one transaction, so the old slot is released and the new one claimed together.

### Idempotency [R]

`Idempotency-Key` (a client-generated UUID) is **required** on appointment creation, rescheduling and payment-initiating endpoints. Mobile networks retry. Records are kept about 24–48 hours and cleaned up by a job.

### Customer-side rules [D]

Should a customer be prevented from holding overlapping appointments at different salons, and is there a maximum number of future bookings per customer to limit abuse? Recommended default: block overlapping appointments for the same customer and allow at most about 5 active future bookings.

---

## 8. Testing Strategy

| Level | Tooling | Scope |
|---|---|---|
| **Unit** | JUnit Jupiter, AssertJ, Mockito (versions managed by Boot) | Availability engine (most tests here), price/duration resolution, lifecycle transitions, policy checks, token rotation logic. Plain Java, no Spring context |
| **Module structure** | `spring-modulith-starter-test`: `ApplicationModules.of(App.class).verify()` | Fails on cycles and on access to another module's internals. Write this **on day one** |
| **Module integration** | `@ApplicationModuleTest` + Modulith `Scenario` | A module plus its event publication and consumption, in isolation |
| **Repository / database** | `@DataJpaTest` + Testcontainers PostgreSQL (`@ServiceConnection`) | Queries, constraints (exclusion, composite FKs, checks), Flyway migrations applied to an empty database |
| **Controller / API** | `@WebMvcTest` (`spring-boot-starter-webmvc-test`) + `spring-security-test` (`jwt()` post-processor) | Request validation, status codes, Problem Details, JSON contract, URL security |
| **Security** | `spring-security-test` + full-context tests | **Tenant-isolation matrix**: for each management endpoint, salon A's owner, salon B's owner, a stylist (own vs any), a customer, and an anonymous caller. Token expiry, refresh rotation and reuse detection |
| **Full integration** | `@SpringBootTest` + Testcontainers + MockMvc (`@AutoConfigureMockMvc`) | End-to-end use cases within the backend |
| **Concurrency** | Integration test with N parallel booking attempts on one slot | **Exactly one succeeds** and the rest get 409. This is the acceptance criterion for double-booking prevention |
| **External adapters** | WireMock (later) + iyzico sandbox (manual/CI-optional) | Signature verification, retries, error mapping |
| **API contract** | Export the OpenAPI spec in CI and diff it against `main` (e.g. oasdiff) [U: choose the tool] | Prevents breaking changes to mobile apps already in users' hands |
| **End-to-end** | Later: API-level smoke suite against staging (REST-assured or Bruno). UI E2E belongs to client repositories (Playwright for web, Maestro/Detox for mobile) | |

**Installed at initialization**: the test starters Initializr adds (`webmvc-test`, `security-test`, `data-jpa-test`, Flyway/OAuth2 test starters if generated), `spring-boot-testcontainers` with the Testcontainers PostgreSQL module, and `spring-modulith-starter-test`. **Later**: WireMock, and optionally jqwik for property-based tests of the availability engine. **Never**: H2.

On Windows, Docker Desktop with the WSL2 backend is required for Testcontainers.

---

## 9. Integration Strategy

| Integration | Needed for | Phase | Approach |
|---|---|---|---|
| **iyzico subscriptions** | Salon billing | 7 (before paid launch) | See below |
| **SMS** | Phone OTP, booking confirmations and reminders | 1 (OTP), 5 | `SmsSender` port; local Turkish provider |
| **Email** | Verification, receipts, notifications | 1, 5 | `EmailSender` port over SMTP |
| **Push** | Booking updates and reminders on mobile | 5 | `PushSender` port; FCM or Expo Push depending on the mobile stack |
| **Maps / geolocation** | Salon location and nearby search | 6 | Client-side map SDK; backend stores lat/lng |
| **Object storage** | Salon, staff and service images | 6 | `ObjectStorage` port, S3-compatible API |
| **AI assistant** | Future | Later | Built on clean application APIs |

### Payment — iyzico (salon subscriptions only)

Verified from docs.iyzico.com [V]:
- The subscription product is structured as **product → pricing plan → subscription**. Plans support **trial periods**. Subscriptions are **credit-card only**. Subscription starts go through the **Checkout Form** (`/v2/subscription/checkoutform/initialize`), and the initial status can be `ACTIVE` or `PENDING`. With a trial, iyzico validates the card with a **1 TL charge that is refunded**.
- **Webhooks** carry `subscription.order.success` and `subscription.order.failure` events. The signature header is **`X-IYZ-SIGNATURE-V3`** (HMAC-SHA256); it must be **enabled by iyzico on request**, and older signature headers are no longer supported. The exact field order for *subscription* event signatures is **[U]**: confirm with iyzico.
- The upgrade endpoint and its options are **[U]** (secondary source only).
- Whether annual intervals are supported on plans is **[U]**: confirm in the Pricing Plan API reference.

Design [R]:
- Use the **hosted checkout form** only. Card data never touches our servers, which keeps PCI scope minimal.
- `billing` wraps iyzico behind a `SubscriptionGateway` port (create customer/subscription checkout, retrieve, cancel, upgrade).
- **Webhook inbox pattern**: verify the signature, store the raw event (deduplicated on provider event ID), return 200 quickly, process asynchronously and idempotently, and **re-fetch the authoritative subscription state from iyzico** rather than trusting the payload. Add a **nightly reconciliation** job.
- An **entitlement** layer decouples billing from booking. `billing` publishes `SubscriptionActivated` / `Lapsed`, and `salon` turns them into a "bookable / listed" state. `booking` never calls iyzico.
- **Manual payment method**: bank transfer (havale/EFT) is common for Turkish B2B. Allow an admin to activate a subscription manually, with an audit entry. The `provider` field is `IYZICO | MANUAL`.
- **E-invoicing (e-Fatura/e-Arşiv)** is a legal obligation for invoicing subscription fees, and iyzico does not provide it. You need an e-invoice integrator; the provider choice is **[D]**. The `billing_profile` holds the tax data.
- Customer appointment payments and deposits are **out of scope**. If added, they become a separate `payments` module plus a `PENDING_PAYMENT` appointment state with a hold expiry. The exclusion constraint's status filter already accommodates this.

### Notifications

- **Email** [R]: send through SMTP behind `EmailSender`, so the vendor (AWS SES, Postmark, SendGrid, Mailgun, Brevo, …) can be switched by configuration. Choose by deliverability, Turkish-language template support, price and KVKK data location. I did **not** verify current vendor pricing or terms **[U]**. Use Mailpit locally.
- **SMS** [R]: Turkish sender IDs (başlık) and operator rules favour **local aggregators** (e.g. Netgsm, İleti Merkezi, Verimor, Mutlucell) over global ones. I did **not** verify their APIs or pricing **[U]**. Compare reliability, OTP delivery speed, API quality and price before choosing. **İYS**: according to secondary sources, purely transactional messages such as appointment confirmations and reminders do not need İYS consent, while anything promotional does [U: confirm with counsel]. Keep transactional templates strictly free of marketing content.
- **Push**: FCM covers Android and iOS (through APNs). If the mobile app is built with Expo, Expo Push is simpler. Decide together with the mobile stack **[D]**.
- **Delivery**: event listener → `notification` row (`scheduled_for`) → a sender job with retry and backoff and a dead/failed state, plus a delivery log for support. Reminders are scheduled rows (e.g. 24 h and 2 h before), cancelled when the appointment changes.

### Maps

The backend stores `latitude`/`longitude` plus structured address (il/ilçe from the reference tables). Owners pin the location in the client's map SDK, so **no server-side geocoding is needed initially**. Nearby search uses a bounding-box index plus haversine ordering; PostGIS comes later if needed. The client map provider (Google Maps, Yandex Maps, Mapbox, Apple) is a frontend decision. Yandex has strong coverage in Turkey [U].

### File storage

- S3-compatible object storage (AWS S3, Cloudflare R2, GCS, or a Turkish provider's S3 API), with MinIO locally once Phase 6 starts.
- Flow: client requests an upload → backend creates a `media_asset` (`PENDING_UPLOAD`) and returns a presigned PUT URL with content-type and size restrictions → client uploads directly → client confirms → backend verifies the object (size, type) → `READY`.
- Serve images through a CDN. Resize either on upload or through CDN transforms. **Strip EXIF** (GPS in staff photos is personal data). Images are never stored in the database.

### AI extensibility

Nothing is added now. Keep application services usable as tools (for example "search availability", "book appointment", "cancel"), with the **same authorization** as the caller. An assistant later calls these use cases, never the database. Before adding Spring AI or a provider, evaluate the KVKK implications of sending personal data to an LLM provider abroad.

---

## 10. Project Directory Structure

```
salon-backend/
├── .github/workflows/ci.yml          # build, test, verify modules, image build (Phase 0)
├── .mvn/wrapper/ , mvnw , mvnw.cmd   # Maven Wrapper (generated)
├── .gitattributes                    # force LF for mvnw/*.sh (Windows → Linux CI)
├── .gitignore  .editorconfig  .env.example
├── compose.yaml                      # local: postgres:18 (later: mailpit, minio)
├── Dockerfile                        # or Buildpacks via spring-boot:build-image
├── README.md  CLAUDE.md              # conventions for humans and agents
├── docs/adr/                         # Architecture Decision Records (this report → ADRs)
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/salonapp/
    │   │   ├── SalonApplication.java
    │   │   ├── shared/                       # small kernel: Money, TimeRange, IDs, errors, paging
    │   │   │   ├── web/                      # GlobalExceptionHandler, ProblemDetail codes, API conventions
    │   │   │   ├── security/                 # SecurityConfig, JWT encode/decode config, CurrentUser, TenantAccess
    │   │   │   └── config/                   # Jackson, clock (injectable Clock), OpenAPI, CORS properties
    │   │   ├── identity/
    │   │   │   ├── (public API types: IdentityApi / UserId / events)  ← module root = public
    │   │   │   ├── web/                      # AuthController, MeController + request/response records
    │   │   │   ├── application/              # RegisterUser, Login, RefreshTokens, VerifyPhone use cases
    │   │   │   ├── domain/                   # UserAccount, RefreshToken, VerificationChallenge + repositories
    │   │   │   └── infrastructure/           # token hashing, OTP generation
    │   │   ├── salon/                        # salon, branch, staff, membership, invitations, policies
    │   │   ├── catalog/
    │   │   ├── scheduling/                   # incl. availability/ (pure engine)
    │   │   ├── booking/
    │   │   ├── customer/
    │   │   ├── review/
    │   │   ├── discovery/
    │   │   ├── billing/
    │   │   │   └── infrastructure/iyzico/    # SubscriptionGateway adapter, webhook verification
    │   │   ├── notification/
    │   │   │   └── infrastructure/{email,sms,push}/
    │   │   ├── media/
    │   │   └── audit/
    │   └── resources/
    │       ├── application.yaml              # production-safe defaults, env-var placeholders
    │       ├── application-local.yaml        # dev conveniences only (no secrets)
    │       ├── db/migration/<module>/V…__*.sql
    │       ├── db/dev-data/                  # local-only sample data
    │       └── messages*.properties          # tr / en user-facing messages
    └── test/
        ├── java/com/salonapp/
        │   ├── ModularityTests.java          # Modulith verify + documentation
        │   ├── support/                      # Testcontainers config, fixtures, JWT helpers
        │   └── <module>/…                    # mirrors main: unit, slice, module, integration tests
        └── resources/application-test.yaml
```

Package responsibilities:

| Package | Responsibility | Rule |
|---|---|---|
| module root | Public API of the module: facade services, IDs, events, query DTOs | The only thing other modules may import |
| `web` | Controllers, request/response **records**, mapping to and from application types | No business logic; no entities in responses |
| `application` | Use cases, transaction boundaries, authorization calls, event publication | One public method per use case is a good default |
| `domain` | Entities, value objects, state transitions, invariants, Spring Data repositories | Rich entities where behaviour exists. Repositories are pragmatically domain-adjacent |
| `infrastructure` | Adapters for external systems, technical helpers | Interfaces (ports) only where an external system exists |
| `shared` | Cross-cutting primitives | Keep it small. Reviewers should push back on additions |

Use API DTOs consistently: **never serialize JPA entities**. Records are immutable and Jackson-3-friendly. Pagination returns a stable DTO (Spring Data's `PagedModel` / VIA_DTO mode) and never serializes `PageImpl` directly.

---

## 11. Development Environment

**Required local tools**
- JDK 25 (Eclipse Temurin)
- Docker Desktop with the WSL2 backend (Windows)
- Git
- IntelliJ IDEA (recommended for Spring/JPA) or VS Code with the Java and Spring extensions
- An HTTP client (IntelliJ HTTP client or Bruno, both of which can live in the repository)
- A database client (DBeaver or IntelliJ's)

Maven is supplied by `mvnw`.

**Docker Compose (local)**: `postgres:18` (pin a minor tag), a named volume, and a health check. Later add `axllent/mailpit` for SMTP, `minio` for object storage, and Redis only if it is adopted. Spring Boot's Docker Compose support starts these automatically on `mvnw spring-boot:run`.

**Configuration and profiles [R]**
- `application.yaml` holds **safe production defaults**, with values injected through environment variables. Profiles: `local` (dev conveniences such as dev-data seed, verbose SQL logging, relaxed CORS) and `test`. **No `prod` profile containing secrets**: production is just the default configuration plus environment variables.
- Typed `@ConfigurationProperties` groups: `salon.security.jwt.*`, `salon.security.cors.*`, `salon.booking.*`, `salon.integrations.iyzico.*`, `salon.integrations.sms.*`, `salon.integrations.storage.*`.

**Environment variables (initial and anticipated)**

| Variable | Phase |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | 0 |
| `SALON_SECURITY_JWT_PRIVATE_KEY`, `SALON_SECURITY_JWT_PUBLIC_KEY`, `SALON_SECURITY_JWT_KEY_ID`, `SALON_SECURITY_JWT_ISSUER` | 1 |
| `SALON_SECURITY_CORS_ALLOWED_ORIGINS` | 1 |
| `SALON_APP_PUBLIC_BASE_URL` (links in emails) | 1 |
| `SPRING_MAIL_*` / `SALON_INTEGRATIONS_SMS_*` | 1/5 |
| `SALON_INTEGRATIONS_IYZICO_API_KEY`, `…_SECRET_KEY`, `…_BASE_URL`, `…_WEBHOOK_SECRET` | 7 |
| `SALON_INTEGRATIONS_STORAGE_*` (endpoint, bucket, access keys) | 6 |

**Quality tools [R]** (minimal, all Maven plugins added manually)
- **Spotless** with palantir-java-format or google-java-format, so formatting is never debated
- **JaCoCo** for coverage reports (no hard gate at first)
- The Modulith verification test
- **Dependabot or Renovate** for dependency updates
- **Not initially**: Checkstyle/PMD (overlap with the formatter), SonarQube (optional later), Error Prone/NullAway (consider later, since Spring now uses JSpecify annotations [V])

---

## 12. Production Readiness

**Security**
- Private database networking.
- Least-privilege database roles.
- Secret manager.
- Key rotation.
- Edge WAF and rate limits.
- Dependency and container image scanning (e.g. Trivy) in CI.
- SBOM.
- Penetration test before public launch.
- Admin endpoints protected by an additional factor (MFA for platform admins) **[D]**.

**Observability**
- **Structured JSON logs** using Boot's built-in structured logging (`logging.structured.format.console`) [U: confirm property values on Boot 4.1].
- Correlation fields: `traceId`, `requestId`, `userId`, `salonId`.
- **No PII in logs**: mask phone numbers and emails, and never log tokens or OTPs.
- Actuator: `health` (liveness and readiness probes), `info` (build and git), metrics. Expose them on a separate management port, never publicly.
- Business metrics: bookings created, booking conflicts (409), notification failures, webhook failures, OTP send rate.
- Tracing (Micrometer/OpenTelemetry) once a backend is chosen.

**CI/CD**
- GitHub Actions [A: the repository will be on GitHub]. On PR: `./mvnw verify` (unit + Testcontainers integration + Modulith verify + Spotless check) and an OpenAPI diff.
- On `main`: build the image (Buildpacks or a multi-stage Dockerfile using `-Djarmode=tools extract`; the old `layertools` mode was removed in 4.1 [V]), scan it, push it to a registry, and deploy automatically to staging.
- Production deploys need **manual approval**. Migrations run before or at rollout. Rolling deploys require backward-compatible migrations (expand/contract).

**Deployment [A/D]**
- The cloud provider is not selected. Stay portable: a stateless container, managed PostgreSQL, S3-compatible storage, and secrets through environment variables. This works on AWS (ECS/App Runner + RDS), GCP (Cloud Run + Cloud SQL), Azure Container Apps, or a Turkish cloud.
- **Hosting region and KVKK**: hosting outside Turkey is a cross-border transfer under amended KVKK Art. 9. Since 1 June 2024 it relies on appropriate safeguards such as **standard contracts, which must be notified to the Authority within 5 business days** [V: secondary legal sources]. Reportedly no adequacy decisions exist yet [U]. This affects the choice of cloud region and of every SaaS vendor (email, SMS, monitoring).
- Run at least two instances behind a load balancer for availability once live. Scheduled jobs need single-execution guarantees (ShedLock or `SKIP LOCKED`).
- Virtual threads (`spring.threads.virtual.enabled`): evaluate under load tests in Phase 8 rather than enabling blindly.

**Backup and recovery**
- Managed point-in-time recovery (e.g. 7–35 days) plus regular snapshots in another region or account.
- **Quarterly restore drills**.
- Object storage versioning.
- Documented RPO/RTO. Proposed default: RPO ≤ 5 minutes, RTO ≤ 4 hours [A].

**Scalability and performance**
- The application tier is stateless and scales horizontally.
- The database is the bottleneck: indexes, DTO projections, no N+1 queries (fetch plans), keyset pagination for feeds.
- Availability caching, read replicas for discovery, and a dedicated search engine only after measuring a need.

**Privacy and KVKK [R/U: legal review required]**
- **Roles**: the platform is the data controller for customer accounts. For salon client records, salons act as controllers and the platform as processor. This needs to be written into the salon subscription agreement and a DPA.
- **Documents**: a privacy notice (aydınlatma metni) at signup. Explicit consent (açık rıza) only where required (e.g. marketing).
- **VERBIS registration**: obligation depends on thresholds [U].
- **Breach notification**: notify the Board within 72 hours, so a breach runbook is needed.
- **Retention**: retention schedule plus anonymization jobs. Keep appointment history for accounting with anonymized personal fields.
- **Data subject requests**: export and delete endpoints for customers.
- **Data minimization**: do not collect national ID (TCKN) for customers. Tax IDs for salon invoicing are stored and protected.
- **Special-category data risk**: free-text client notes may capture health data (allergies, skin conditions). Show a UI warning and restrict access to client notes.

---

## 13. Risks and Unresolved Decisions

### Pitfalls to watch

| Risk | Mitigation |
|---|---|
| Tenant data leakage | `salon_id` everywhere, composite FKs, one `TenantAccess` gate, 404 for other tenants' resources, isolation test matrix, RLS later |
| Double booking | Exclusion constraint + concurrency test as acceptance criterion |
| Time zone bugs | `Instant`/`timestamptz` only; branch `ZoneId`; UTC everywhere; DST tests |
| Turkish locale bugs (`i`/`İ`) | `Locale.ROOT` for normalization; Turkish locale tests |
| Boot 4 library compatibility (Jackson 3, Testcontainers 2) | Verify each library; prefer Spring-portfolio solutions |
| Breaking mobile clients | Additive-only changes within `/v1`; clients must tolerate unknown enum values; `GET /api/v1/meta/app-config` returns min/force-update version; OpenAPI diff in CI |
| Scheduling scope creep | Weekly template + exceptions only; **no RRULE/recurring appointments in v1** |
| SMS cost and fraud (SMS pumping) | Per-number and per-IP OTP limits, CAPTCHA on abuse, country restriction (+90) initially |
| Spring Boot support window | 4.1 OSS ends mid-2027; plan to upgrade each minor within ~6 months of its release |
| Over-engineering | No broker, Redis, IdP, RLS or PostGIS until a measured need exists |

### Decisions requiring your approval (with recommended defaults)

| # | Decision | Recommended default |
|---|---|---|
| D1 | **Group ID / base package / company domain** | `com.salonapp` placeholder. Replace it with a domain you own **before generation**. **Decided: `com.salon`** |
| D2 | Auth approach | Self-issued JWT + rotating refresh tokens; no external IdP. **Accepted → ADR-0003** |
| D3 | Customer login | Email + password; verified phone required before first booking; phone-OTP login later. **Accepted → ADR-0007** |
| D4 | Tenant shape | Salon (tenant) → Branches from day one; single-branch salons simply have one branch. **Accepted → ADR-0008** |
| D5 | Booking confirmation | Per-branch `auto_confirm`, default **true**. **Accepted → ADR-0009** |
| D6 | Multi-service bookings | Supported in the data model from v1 (one staff member per appointment) |
| D7 | "Any available staff" | Engine supports it; UI in phase 2 |
| D8 | Walk-in and phone bookings by salon | Supported via `salon_client` (no customer account needed) |
| D9 | Subscription unit and tiers | Per salon, tiers limit branches and staff; annual price; **trial length** (14 vs 30 days) is a business call |
| D10 | Lapsed subscription | Hidden from discovery and no new online bookings; existing appointments honoured; data kept for N days, then retention policy applies |
| D11 | Salon onboarding | Self-serve signup → `PENDING_REVIEW` → admin approval before public listing |
| D12 | Customer appointment payments and deposits | Out of scope for v1 |
| D13 | Reviews | Only for `COMPLETED` appointments, one per appointment, salon may reply once, admin moderation |
| D14 | Cancellation defaults | Customer may cancel or reschedule up to 2 h before (configurable per branch) |
| D15 | Staff visibility of client phone numbers | Own appointments only, unless `CLIENT_VIEW` |
| D16 | Hosting provider and region (KVKK) | Undecided. Shortlist a Turkish region vs EU region with standard contracts; needs legal input |
| D17 | Email, SMS and push vendors; e-invoice integrator | Decide per phase after comparing current docs and pricing |
| D18 | Platform admin MFA | Yes (TOTP) before launch |
| D19 | Customer limits | No overlapping appointments for the same customer; ≤ 5 active future bookings |

---

## 14. Implementation Roadmap

| Phase | Content | Depends on |
|---|---|---|
| **0. Foundation** | Generate the project, `.gitattributes`/`.gitignore`/`.editorconfig`, `compose.yaml`, base YAML config (`open-in-view=false`, `ddl-auto=validate`, UTC), error model (Problem Details + codes), springdoc, Modulith verify test, Testcontainers base config, Flyway baseline (extensions `btree_gist`), structured logging, actuator config, CI pipeline, Spotless, CLAUDE.md + first ADRs | — |
| **1. Identity** | Registration, login, JWT issuance and validation, refresh rotation, email verification, phone OTP (logging stub adapter for SMS), password reset, platform admin bootstrap, audit module | 0 |
| **2. Salon & access** | Salon, branch, staff membership/roles/permissions, invitations, `TenantAccess`, tenant-isolation test matrix, salon onboarding statuses, il/ilçe reference data | 1 |
| **3. Catalog & scheduling** | Services, overrides, staff skills, working hours, time off, closures, **availability engine** with extensive unit tests | 2 |
| **4. Booking** | Appointments + items + exclusion constraint, idempotency, lifecycle, policies, customer and salon views, `salon_client`, Modulith event registry, **concurrency test** | 3 |
| **5. Notifications** | Event listeners, email/SMS/push adapters (real vendors), reminders, delivery log, device tokens | 4 |
| **6. Discovery, favorites, reviews, media** | Public search (city/district/service/distance), favorites, reviews and ratings, image upload | 3 (discovery), 4 (reviews), 2 (media) |
| **7. Billing** | Plans, iyzico subscriptions, trials, webhook inbox, reconciliation, entitlements → bookable state, manual payments, e-invoice integration | 2. Can run in parallel with 5–6 but must be finished before paid launch |
| **8. Production hardening** | Observability backend, edge rate limiting/WAF, load tests (booking concurrency, availability), backup restore drill, security review/pentest, KVKK documents and DSR endpoints, admin MFA | All |

A beta with trial-only salons can start after Phase 5 or 6. Billing (7) gates the paid launch, not the beta.

---

## Final Initialization Checklist

- [ ] **D1**: decide the real Group ID and base package (company domain).
- [ ] Approve or override D2–D5 (they affect Phase 1–2 schema).
- [ ] Install JDK 25 (Temurin), Docker Desktop (WSL2), and an IDE. Verify `java -version` and `docker info`.
- [ ] Open start.spring.io and enter the configuration below. Confirm the Boot version shown is still **4.1.x GA** (if 4.2.x GA with ≥1 patch is listed, prefer it).
- [ ] Generate and unzip into `salon-backend/`.
- [ ] **Inspect `pom.xml`**: `spring-boot-starter-webmvc`, `spring-boot-starter-flyway` **and** `flyway-database-postgresql`, `spring-boot-starter-security-oauth2-resource-server`, Testcontainers 2.x PostgreSQL module, Modulith BOM and starters, matching `*-test` starters. Add whatever is missing.
- [ ] Add `springdoc-openapi-starter-webmvc-ui` (latest 3.0.x that supports Boot 4.1, checked on Maven Central).
- [ ] Rename to `application.yaml` if Properties was generated. Set `spring.jpa.open-in-view=false`, `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`.
- [ ] Check the generated `compose.yaml` and pin `postgres:18.x`.
- [ ] Add `.gitattributes` (`mvnw text eol=lf`, `*.sh text eol=lf`), `.gitignore` additions (`.env`), `.editorconfig`, `.env.example`.
- [ ] Add `ModularityTests` (Modulith verify) and a Testcontainers-backed `contextLoads`.
- [ ] Run `./mvnw verify`. It must be green before the first commit.
- [ ] First commit on a branch; set up CI; write ADR-001..00n from this report; create CLAUDE.md with the conventions in §4/§10.

---

## Copyable Spring Initializr Configuration

```
Project:        Maven
Language:       Java
Spring Boot:    4.1.2
Group:          com.salonapp            ← replace with your domain (D1)
Artifact:       salon-backend
Name:           salon-backend
Description:    Salon appointment and salon management platform – backend API
Package name:   com.salonapp
Packaging:      Jar
Configuration:  YAML
Java:           25

Dependencies:
  Spring Web
  Spring Security
  OAuth2 Resource Server
  Spring Data JPA
  PostgreSQL Driver
  Validation
  Flyway Migration
  Spring Boot Actuator
  Testcontainers
  Spring Modulith
  Docker Compose Support
  Spring Configuration Processor

Manual (after generation):
  org.springdoc:springdoc-openapi-starter-webmvc-ui : 3.0.x (latest compatible)
```

Equivalent pre-filled URL (opens the UI only; you still click Generate):

```
https://start.spring.io/#!type=maven-project&language=java&platformVersion=4.1.2&packaging=jar&jvmVersion=25&groupId=com.salonapp&artifactId=salon-backend&name=salon-backend&description=Salon%20appointment%20and%20salon%20management%20platform%20-%20backend%20API&packageName=com.salonapp&dependencies=web,security,oauth2-resource-server,data-jpa,postgresql,validation,flyway,actuator,testcontainers,modulith,docker-compose,configuration-processor
```

---

## Verification (after you generate the project)

1. `./mvnw verify` passes with Docker running. The generated test should start a PostgreSQL Testcontainer, and Flyway should report "Schema is up to date" or apply the baseline.
2. `./mvnw spring-boot:run` starts `compose.yaml` PostgreSQL automatically, and `GET /actuator/health` returns `UP` with liveness and readiness groups.
3. `ApplicationModules.of(SalonApplication.class).verify()` passes (trivially at first; it guards every later phase).
4. `/v3/api-docs` and Swagger UI load once springdoc is added.
5. The dependency tree (`./mvnw dependency:tree`) contains no `spring-boot-starter-web` (deprecated), no H2, no Liquibase, and no Lombok.

---

## Generated Project Audit (commit `9f3df55 spring init`, 2026-11-02)

**Matches the recommendation:** Maven; Boot **4.1.2**; Java **25**; Jar; `spring-boot-starter-webmvc`; security + `spring-boot-starter-security-oauth2-resource-server`; data-jpa; validation; `spring-boot-starter-flyway` + `flyway-database-postgresql`; actuator; PostgreSQL driver (runtime); `spring-boot-docker-compose` (runtime, optional); configuration processor (annotation processor path); Modulith **2.1.1** BOM + `starter-core` + `starter-jpa` + `starter-test`; **springdoc 3.2.0**; per-technology `*-test` starters; Testcontainers 2 (`testcontainers-junit-jupiter`, `testcontainers-postgresql`, package `org.testcontainers.postgresql`); `.gitattributes` keeps `mvnw` at LF; Maven Wrapper 3.3.4 → Maven 3.10.0. No Lombok, H2, Liquibase, DevTools or deprecated `spring-boot-starter-web`.

**Previously unverified, now resolved:** the starter mapping for Spring Web, inclusion of the Flyway PostgreSQL module, the Testcontainers 2 artifact names (including the JUnit artifact), springdoc availability and version, and the Modulith version.

**Deviations and gaps → Phase 0 fixes**

| # | Finding | Action |
|---|---|---|
| G1 | **JDK on PATH is Temurin 21.0.4**, but the project targets 25 → the compiler will fail with "release version 25 not supported" | Install Temurin 25; set `JAVA_HOME` and PATH, and the IDE project SDK |
| G2 | **The Docker daemon is not running** (`dockerDesktopLinuxEngine` pipe missing) → `contextLoads` (Testcontainers) and `spring-boot:run` (Compose) will fail | Start Docker Desktop (WSL2 backend) |
| G3 | groupId/package is `com.salon`, not the `com.salonapp` placeholder (D1) | Acceptable if it is the final choice. Change it now or never, since no code depends on it yet |
| G4 | `<name/>` and `<description/>` are empty in `pom.xml` | Fill them in (cosmetic) |
| G5 | `application.properties` instead of YAML; no base settings | Convert to `application.yaml`; add `open-in-view=false`, `ddl-auto=validate`, `jdbc.time_zone=UTC` |
| G6 | `compose.yaml` and `TestcontainersConfiguration` use **`postgres:latest`** and placeholder credentials (`mydatabase`/`myuser`/`secret`) | Pin `postgres:18.x` in both; use project DB name and user (local-only credentials) |
| G7 | `spring-modulith-starter-jpa` is already present, so the event publication registry is active | Create the `event_publication` table via a Flyway migration before switching to `ddl-auto=validate` [U: confirm the schema in Modulith 2.1 docs] |
| G8 | Extra Modulith runtime artifacts: `observability-api/core`, `actuator`, `runtime` | Keep them (harmless; module tracing and actuator endpoint). Revisit with the tracing decision |
| G9 | Empty `src/main/resources/static` and `templates`, and `db/migration`, are not tracked by git (git ignores empty directories) | Delete `static`/`templates` (REST-only API). The migration folder appears with the first migration |
| G10 | `.gitignore` lacks `.env` | Add `.env` (and keep `.env.example`) |
| G11 | No `ModularityTests` (Modulith verify), no CI, no CLAUDE.md, no ADRs | Phase 0 |

## Phase 0 Execution Plan (approved scope: G1–G2 check, G3 final, fix G4–G11)

**Status checks (read-only, done)**
- **G1 fixed for Maven**: Temurin **25.0.5** is installed at `%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25.0.5.8-hotspot`, and the user-level `JAVA_HOME` points to it (`mvnw` uses `JAVA_HOME`). The VS Code session's PATH still finds `java` 21 first because it predates the install. Restart VS Code and terminals. The system PATH still lists the JDK 21 and Oracle `javapath` entries *after* JDK 25, so restarted shells resolve to 25.
- **G2 fixed**: Docker Desktop is running (client/server 29.9.2, context `desktop-linux`).
- **G3**: `com.salon` is the final choice. No change.
- **New finding G12**: `mvnw` is committed as `100644` (not executable), so Linux CI fails with "permission denied". Fix: `git update-index --chmod=+x mvnw`.

**Changes to make**

| # | File | Change |
|---|---|---|
| G4 | `pom.xml` | `<name>salon-backend</name>`, `<description>Salon appointment and salon management platform - backend API</description>`. Leave the other empty Initializr placeholder elements alone |
| G5 | `src/main/resources/application.properties` → **delete**; create `application.yaml` | `spring.application.name`, `spring.jpa.open-in-view: false`, `spring.jpa.hibernate.ddl-auto: validate`, `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`. Nothing else yet (no premature config) |
| G6 | `compose.yaml` | `postgres:18` (Debian-based, not alpine: glibc/ICU collation behaviour matters for Turkish text), `POSTGRES_DB=salon`, `POSTGRES_USER=salon`, `POSTGRES_PASSWORD=salon` (local only), keep the random host port (Boot's Compose support wires it) |
| G6 | `src/test/java/com/salon/TestcontainersConfiguration.java` | `postgres:latest` → `postgres:18` |
| G7 | `src/main/resources/db/migration/V2026_11_02_1200__create_event_publication.sql` | Modulith 2.1.1 PostgreSQL "current standard schema", copied from the official reference appendix (`event_publication`: id UUID PK, listener_id, event_type, serialized_event TEXT, publication_date/completion_date/last_resubmission_date TIMESTAMPTZ, status TEXT, completion_attempts INT, plus the hash index on serialized_event and the completion_date index). It must pass Hibernate `validate` against Modulith's JPA entity (`EVENT_PUBLICATION`, fields id/publicationDate/listenerId/serializedEvent/eventType/completionDate/lastResubmissionDate/completionAttempts/status). `./mvnw verify` proves it. **Fallback if validation complains about a column type**: adjust the column type in this same uncommitted migration |
| G8 | — | Keep the Modulith runtime and observability artifacts |
| G9 | `src/main/resources/static/`, `templates/` | Delete the empty directories (untracked) |
| G10 | `.gitignore` | Add a `### Local environment ###` block with `.env` |
| G11 | `src/test/java/com/salon/ModularityTests.java` | `ApplicationModules.of(SalonBackendApplication.class).verify()` |
| G11 | `.github/workflows/ci.yml` | On push/PR to `main`: checkout → `actions/setup-java` (temurin, 25, maven cache) → `./mvnw -B verify`. Ubuntu runners have Docker, so Testcontainers works |
| G11 | `CLAUDE.md` | Concise project conventions: stack and versions, commands, module rules, database/migration rules, time and tenancy rules, testing rules, dependencies to avoid, Windows notes. Points to `docs/` |
| G11 | `docs/architecture/initial-architecture-report.md` | Copy of this report (source of truth for ADRs) |
| G11 | `docs/adr/0001…0006` | Short ADRs (context / decision / consequences): modular monolith + Modulith; shared-schema multi-tenancy; self-issued JWT + rotating refresh tokens; Flyway; database-enforced double-booking prevention; time and money representation |
| G12 | git index | `git update-index --chmod=+x mvnw` |

**Not done (outside the approved scope; listed as follow-ups)**: Spotless/JaCoCo plugins, `.editorconfig`, `btree_gist` migration (lands with booking in Phase 4), security configuration (Phase 1), committing and pushing (left for the user to review first).

**Verification**
1. `./mvnw -B verify` with `JAVA_HOME` = JDK 25 and Docker running. Expect `contextLoads` (Testcontainers PG 18 + Flyway applies the event_publication migration + Hibernate validate passes) and `ModularityTests` green.
2. `git status` shows only the intended changes; `git ls-files -s mvnw` shows `100755`.

## Sources (checked 2026-11-02)

- Spring Initializr metadata: https://start.spring.io/metadata/client (Boot 4.1.2 default / 4.0.9, Java 27/25/21/17, dependency ids and ranges)
- Spring Boot 4.0 Migration Guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- Spring Boot 4.1 Release Notes: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes
- Oracle Java SE Support Roadmap: https://www.oracle.com/java/technologies/java-se-support-roadmap.html
- Spring Boot support windows (secondary): https://endoflife.ai/spring-boot/4.0 , https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026
- springdoc-openapi: https://github.com/springdoc/springdoc-openapi
- Spring Modulith 2.0 GA: https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/ ; releases: https://github.com/spring-projects/spring-modulith/releases
- Testcontainers 2 renames (secondary): https://docs.openrewrite.org/recipes/java/testing/testcontainers/testcontainers2dependencies
- PostgreSQL 18 UUID functions: https://www.postgresql.org/docs/18/functions-uuid.html
- iyzico Subscription: https://docs.iyzico.com/en/products/subscription , https://docs.iyzico.com/en/products/subscription/subscription-implementation
- iyzico Webhooks: https://docs.iyzico.com/en/advanced/webhook
- KVKK cross-border transfers (secondary legal commentary): https://koksal.av.tr/en/knowledge-centre/how-to-compliantly-transfer-data-abroad-from-turkiye/ , https://www.gide.com/?p=84031
- İYS transactional vs commercial messages (secondary): https://www.cottgroup.com/en/component/zoo/item/ileti-yonetim-sistemi-ve-yeni-uygulamalar
