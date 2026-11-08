# Project status

Last updated: 2026-11-08. Phases 0–4 are done and committed; Phase 4 is `b187eee`, and CI is green on it. Phase 5 waits for the go-ahead.

Roadmap: `docs/architecture/initial-architecture-report.md` §14. Binding decisions: `docs/adr/` (index in `docs/adr/README.md`).

## Phases

| Phase | Status | Commit |
|---|---|---|
| 0. Foundation | Done, except the deferred items below | `3429fc6` |
| 1. Identity (registration, login, JWT and refresh tokens, email and phone verification, password reset, audit) | Done | `3612321` |
| 2. Salons and access (salons, branches, staff and roles, invitations, reactivation, review lifecycle, tenant isolation, branch scopes) | Done | `adc074b` |
| 3. Catalog and scheduling (services, sections, offerings, staff skills, opening hours, special days, closures, working hours, time off, availability engine, appointment-conflict boundary) | Done (506 tests), ADR-0012 | `47e8537`, `ee1db7c`, `a088dca` |
| 4. Booking (appointments, idempotency, lifecycle and expiry, salon clients, salon bookings, conflict override, events) | Done (680 tests, randomized-order run green), ADR-0013 | `b187eee` |
| **5. Notifications (outbox, real providers)** | **Next. Not started** | — |
| 6. Discovery, favorites, reviews, media | Planned | — |
| 7. Billing (iyzico, e-invoice) | Planned | — |
| 8. Production hardening | Planned | — |

Deferred from Phase 0:
- `.editorconfig`, Spotless and JaCoCo plugins.
- Structured logging configuration and actuator exposure settings.
- Mockito agent configuration: Mockito currently self-attaches with a JDK warning.

## Phase 3 summary (ADR-0012)

- **Modules:** `catalog` and `scheduling`, with dependencies `salons ← catalog ← scheduling`. `salons` gained the public types `SalonDirectory`, `BranchInfo` and `StaffInfo`; `TenantAccess` gained `requireForStaffMember`.
- **Catalog** (`/salons/{id}/services`, `/service-sections`, `/staff/{id}/skills`):
  - platform category taxonomy (13 seeded categories; public `GET /public/service-categories`);
  - salon services with price, duration, buffer, a "starting from" flag and an online-bookable flag; archive and restore;
  - optional salon sections;
  - explicit per-branch offerings with price, duration and buffer overrides;
  - explicit staff skills with a duration override.
  - Public: `GET /public/branches/{id}/services`.
- **Scheduling:**
  - per branch: opening hours, special days and closures (`/salons/{id}/branches/{id}/opening-hours|special-days|closures`);
  - per staff member: working-hour periods per branch (`/salons/{id}/staff/{id}/working-hours`, `PUT .../branches/{id}/working-hours`) and time off (`/salons/{id}/staff/{id}/time-off`).
- **Availability:** `GET /public/branches/{id}/availability?serviceIds=..&from=..&to=..[&staffId=..]`. A pure engine with unit tests, including DST cases.
- **Tests:** engine and value unit tests; integration tests for catalog, scheduling and availability; a race test for the cross-branch hours rule; constraint tests by name; the tenant-isolation matrix extended to every new endpoint (249 cases).
- **Decision 21 boundary (2026-11-05):**
  - Opening hours, special days (set and remove), closures, working hours and time off are checked against future blocking appointments from `scheduling.BlockingAppointments`.
  - There is no implementation until booking, so nothing is blocked yet. Tests use `TestBlockingAppointments`.
  - `ApiException` can carry extra problem members (here `conflictCount` and `appointments`).

## Phase 4 summary (ADR-0013, decisions 27–41; ADR-0005, ADR-0009, ADR-0012 decisions 21–26)

- **Module:** `booking`, with dependencies `salons ← catalog ← scheduling ← booking`.
  - Public: `AppointmentEvent` (domain events, ids only).
  - The extension point `BlockingAppointments` moved to the `salons` root (decision 26). `salons.AppointmentConflicts` holds the shared 409 and override logic.
  - `scheduling.BookableSlots` checks one exact start. `TenantAccess.requireAny` is new, and `SalonPermission.APPOINTMENT_CONFLICT_OVERRIDE` (OWNER, MANAGER).
- **Customer** (`/me/appointments`):
  - book with an `Idempotency-Key`, a staff member or "anyone" (decision 28);
  - list (`UPCOMING`/`PAST`), read, cancel and reschedule until the branch cutoff.
  - Requires a public branch and a verified phone (`403 PHONE_NOT_VERIFIED`).
- **Salon** (`/salons/{id}/appointments`, `/clients`, `/branches/{id}/availability`):
  - walk-in clients;
  - salon bookings: any service and minute, 15 minutes in the past to 365 days ahead, inactive branches too;
  - confirm, reject, cancel (reason required), reschedule, complete, no-show, resolve attention, internal note;
  - filters `needsAttention` and `awaitingOutcome`; history per appointment.
- **Rules:**
  - exclusion constraints for staff (buffer included) and customer (platform-wide, buffer excluded);
  - at most 5 active appointments per customer, under a per-customer lock;
  - bookings take the salon row `FOR SHARE`.
- **Conflicts (decisions 21, 25, 26):** schedule, staff and catalog changes that affect future appointments get `409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS`. `conflictOverride: {reason}` applies the change and flags the appointments.
- **Jobs:** pending expiry every minute (`FOR UPDATE SKIP LOCKED`), idempotency cleanup hourly (`BookingScheduler`; disabled in tests with `salon.booking.jobs.enabled=false`).
- **Availability:** blocking appointments are absences.
- **Tests (174 new, 680 total):**
  - customer and salon booking, conflicts and override, jobs and events;
  - concurrency: the ADR-0005 acceptance test (8 concurrent bookings, one winner), "any staff" spread, customer overlap and limit races, booking vs. time off, idempotent retries;
  - constraints by name, the time-storage regression, and unit tests (appointment lifecycle, error classification, exact-start check);
  - tenant isolation matrix 339 cases.
- **Defects found and fixed:**
  - Phase 3: local `time` values were stored shifted by the JVM offset (`hibernate.jdbc.time_zone`, ADR-0006). Proven by `LocalTimeStorageIntegrationTests`, then fixed.
  - Phase 4 (found by the acceptance test): exclusion-constraint deadlocks between concurrent bookings returned 500. They are now retried (ADR-0005).

## Phase 5 scope (from roadmap and ADRs)

- Listeners for `AppointmentEvent` (`@ApplicationModuleListener`, idempotent) through the Modulith publication registry: confirmations, cancellations, reschedules, expiry, needs-attention notices, and reminders (for example to record outcomes, decision 33).
- The after-commit outbox for email and SMS (ADR-0011): move the existing synchronous identity and invitation messages onto it, then choose real providers (D17).
- Push notifications and device tokens, and a delivery log.
- Open before Phase 5: vendor choice (D17), and whether customers are notified of every salon-side change.

## Work log

| Date | Work | Commit |
|---|---|---|
| 2026-11-04 | CI fix: `mvnw` committed without the executable bit (`./mvnw: Permission denied` on the Linux runner) | `a90cce1` |
| 2026-11-04 | Phase 3 decision register (decisions 1–20) agreed; Phase 3 implemented: `catalog` and `scheduling` modules, availability engine, ADR-0012 | `47e8537` |
| 2026-11-04 | Catalog error check: the VS Code "non-exposed type" errors are a stale IDE model (`ModularityTests` passes); `DUPLICATE_SERVICE` moved into `CatalogErrors` | `47e8537` |
| 2026-11-04 | Pre-Phase-4 checks: CI green, `mvnw` line endings and mode, build time (~3 min vs. 20 min limit), first real start of the app (`local` profile, migrations, OpenAPI with all Phase 3 paths), test-log warnings. Adjustments: `INVALID_SERVICES` moved into `SchedulingErrors`; hours kept at a branch a staff member left are now tested and documented | `ee1db7c` |
| 2026-11-05 | Decisions 21–24 recorded. Decision 21 boundary built: extension point `scheduling.BlockingAppointments`, checks on opening hours, special days, closures, working hours and time off (`409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS`); `ApiException` problem properties; time-off creation takes the salon lock; 13 new tests (506 total) | `ee1db7c` |
| 2026-11-05 | Decisions 25 (override permission) and 26 (staff and catalog changes) recorded; docs reviewed for stale statements | `a088dca` |
| 2026-11-07 | Phase 4 decision register (27–41) agreed, with adjustments to 28 (exact-start candidates, retry), 33 (no automatic completion) and 37 (staff may book inactive branches, 365 days ahead); ADR-0013 | `b187eee` |
| 2026-11-07 | Phase 4 implemented: `booking` module, extension point moved to `salons`, decision 26 checks and the override, salon availability, jobs, events; 174 new tests (680 total) | `b187eee` |
| 2026-11-07 | Defects fixed: shifted `time` storage from `hibernate.jdbc.time_zone` (Phase 3, ADR-0006); exclusion-constraint deadlocks returned 500 (ADR-0005). `PhoneNumbers` moved to `shared` for walk-in phones | `b187eee` |
| 2026-11-08 | Randomized-order run, docs updated (ADR-0001, 0005, 0006, 0007, 0009, 0010, 0012, 0013, index, report, CLAUDE.md), local app start | `b187eee` |

## Open product decisions

| Topic | Current behavior | Recommendation / when |
|---|---|---|
| Editing walk-in client records | Not possible (create, list, read only) | Decide who may edit (recommended: `APPOINTMENT_MANAGE_ANY` + `CLIENT_VIEW`) |
| Merging duplicate client records (walk-in vs. linked) | Not merged | Later phase (ADR-0013) |
| Salon cancelling a past, still confirmed appointment | Allowed (decision 29 says "future") | Recommended: allow only before the end; record `NO_SHOW` or `COMPLETED` otherwise |
| Notifying customers of salon-side changes | Events are published; nothing is sent (decision 39) | Phase 5 |
| Reminders for unrecorded outcomes (`awaitingOutcome`) | Filter only | Phase 5 notifications |
| Branch-scoped managers | Managers are salon-wide | Keep, unless explicitly changed (ADR-0010) |
| Staff self-service profile | Staff cannot edit their own profile | Add a `/me` profile endpoint when clients need it |
| Editing a salon while `PENDING_REVIEW` | Allowed | Decide before launch (admin may review a moving target) |
| Auditing failed security attempts (e.g. wrong-account invitation use) | Not audited | Consider in Phase 8 |
| Category taxonomy changes | Seeded by migration; no admin endpoint | Add admin editing when the list needs to change often |
| Report decisions D6–D19 (report §13) | D6, D7, D19 accepted in ADR-0012; D8, D14, D15 in ADR-0013; D11 implemented | Revisit per phase |
| Hosting region (KVKK), vendors, e-invoice integrator (D16, D17) | Undecided | Before Phase 5 and 7 |

## Known limitations

- **Messaging:** email and SMS are sent synchronously inside transactions, through logging placeholders only. Phase 5 replaces this with an after-commit outbox; until then, no real provider (ADR-0011).
- **Web refresh cookie:** the web cookie variant of the refresh endpoint is not built; refresh tokens are sent in the body (ADR-0003).
- **Districts:** stored as free text; only the 81 provinces are reference data.
- **Concurrency tests:** they use MockMvc in one JVM, not real HTTP. The salon row lock (now also taken `FOR SHARE` by every booking) and the booking path have not been load-tested (Phase 8).
- **Rate limits:** public availability and booking have no rate limit yet (Phase 8). A customer is limited only by the 5-appointment rule.
- **Overlapping working hours across branches** are prevented by the application under the salon row lock, not by a database constraint (ADR-0012).
- **Hours at a branch a staff member left** are kept (unassigning does not delete them; `salons` cannot depend on `scheduling`). They no longer produce availability, but they still count in the cross-branch overlap check, so new hours elsewhere can get `409 WORKING_HOURS_CONFLICT` naming the old branch. Clearing them (`PUT .../branches/{old}/working-hours` with no periods) is always allowed. A cleanup through a `salons` membership event is possible if this proves confusing.
- **DST fall-back:** a local time that occurs twice is offered only at its earlier instant. Turkey has no DST, so this matters only for future markets.
- **Time format:** responses carry local times as `"09:00:00"`; requests accept `"09:00"`.
- **Overrides on bodiless endpoints** (archive, deactivate, delete special day, remove offering) need an optional JSON body; some HTTP clients cannot send a body with `DELETE` (ADR-0013).
- **Expiry precision:** the job runs every minute, so a pending appointment can stay `PENDING` up to a minute after its deadline. Confirming it then fails (`409`), and it still blocks the slot until the job runs.
- **Unmarked past appointments** stay `CONFIRMED` until staff record the outcome (decision 33).

## Environment notes (this machine)

- JDK 25 is installed at user level, and `JAVA_HOME` points to it. Restart VS Code and terminals if `java -version` still shows 21.
- Docker Desktop must be running for tests (Testcontainers) and `spring-boot:run` (Compose). It is installed per user (`%LOCALAPPDATA%\Programs\DockerDesktop`).
- The JVM default zone here is Europe/Istanbul (+03:00), unlike CI (UTC). Time-zone bugs can show only locally (see ADR-0006).
- The GitHub CLI is logged in: check CI with `gh run list` (repository `aubreyzip/salon-backend`). Since Phase 4 the CI build takes about 6.5 minutes (job limit 20 minutes).
- CI: `mvnw` must stay executable in git (mode `100755`, fixed in `a90cce1`). Files created on Windows lose the bit: use `git update-index --chmod=+x <file>`.
