# Project status

Last updated: 2026-11-04 (after commit `47e8537 feat: phase 3 progression`, CI green; pre-Phase-4 checks done).

Roadmap: `docs/architecture/initial-architecture-report.md` §14. Binding decisions: `docs/adr/` (index in `docs/adr/README.md`).

## Phases

| Phase | Status | Commit |
|---|---|---|
| 0. Foundation | Done, except the deferred items below | `3429fc6` |
| 1. Identity (registration, login, JWT and refresh tokens, email and phone verification, password reset, audit) | Done | `3612321` |
| 2. Salons and access (salons, branches, staff and roles, invitations, reactivation, review lifecycle, tenant isolation, branch scopes) | Done | `adc074b` |
| 3. Catalog and scheduling (services, sections, offerings, staff skills, opening hours, special days, closures, working hours, time off, availability engine) | Done (506 tests, randomized-order runs green), ADR-0012 | `47e8537` |
| **4. Booking** | **Next. Not started** | — |
| 5. Notifications (outbox, real providers) | Planned | — |
| 6. Discovery, favorites, reviews, media | Planned | — |
| 7. Billing (iyzico, e-invoice) | Planned | — |
| 8. Production hardening | Planned | — |

Deferred from Phase 0:
- `.editorconfig`, Spotless and JaCoCo plugins.
- Structured logging configuration and actuator exposure settings.
- `btree_gist` extension migration (add with booking, ADR-0005).
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

## Phase 4 scope (from roadmap and ADRs)

- **Appointments:** appointments with items, a `btree_gist` migration and the exclusion constraint (ADR-0005). A Testcontainers migration test must prove that the range expression works.
- **Booking flow:** `Idempotency-Key`; snapshots of price and duration from `ServiceCatalog.requirements(...)`; availability re-check; `SLOT_UNAVAILABLE` on constraint violation.
- **Lifecycle:** status transitions and the confirmation mode per branch (ADR-0009); cancellation policies; an expiry job.
- **Availability:** add blocking appointments to the engine's absences (`AvailabilityService`), and a salon-side availability and booking path (decision #20: every service, no minimum notice).
- **Clients and views:** `salon_client` (walk-ins and phone bookings), customer and salon appointment views, and the Modulith event registry for `AppointmentBooked`.
- **Decided for Phase 4 (ADR-0012, decisions 21–24, 2026-11-05):**
  - **21, schedule changes vs. appointments:**
    - Implement `scheduling.BlockingAppointments` in booking. With an implementation in place, the existing checks start rejecting conflicting changes with `409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS`.
    - Booking takes the salon row lock `FOR SHARE`, so it cannot race a schedule change.
    - Build the explicit emergency override (reason required, affected appointments flagged for resolution, records kept, visible to authorized staff) and the resolution workflow.
  - **22, inactive branches:** appointments stay and are not cancelled. Read access follows ADR-0010 unchanged.
  - **23, customer overlap:** no overlapping active appointments per account-linked customer, platform-wide, buffer excluded. Enforced by an application check plus a second exclusion constraint (ADR-0005).
  - **24, customer limit:** at most 5 `PENDING`/`CONFIRMED` future appointments per account-linked customer, platform-wide, staff bookings included. Enforced under a per-customer row lock.
- **Still open for Phase 4** (not covered by decision 21; recommendation in brackets):
  - **Override permission:** which permission allows the emergency override. [A new `SCHEDULE_OVERRIDE` permission for OWNER and MANAGER]
  - **Other changes with future appointments:** staff deactivation, branch unassignment, `bookable = false`, skill removal, service archiving and offering removal. [Treat like decision 21: reject with the same 409, override allowed]

## Open product decisions

| Topic | Current behavior | Recommendation / when |
|---|---|---|
| Client privacy for receptionists and staff | No client records yet | Branch-scoped roles see clients with appointments in their branches (Phase 4, ADR-0010) |
| Schedule changes with existing appointments | **Decided (21):** reject with `409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS`; explicit emergency override with reason. Validation boundary built; inactive until booking implements `BlockingAppointments` | Override permission and other change types: Phase 4 (see above) |
| Appointments in inactive branches | **Decided (22):** kept, not cancelled; normal read access rules | Phase 4 |
| Customer limits (D19) | **Decided (23, 24):** no platform-wide overlap (buffer excluded); at most 5 active future appointments | Phase 4 |
| Branch-scoped managers | Managers are salon-wide | Keep, unless explicitly changed (ADR-0010) |
| Staff self-service profile | Staff cannot edit their own profile | Add a `/me` profile endpoint when clients need it |
| Editing a salon while `PENDING_REVIEW` | Allowed | Decide before launch (admin may review a moving target) |
| Auditing failed security attempts (e.g. wrong-account invitation use) | Not audited | Consider in Phase 8 |
| Category taxonomy changes | Seeded by migration; no admin endpoint | Add admin editing when the list needs to change often |
| Report decisions D6–D19 (report §13) | D6, D7, D19 accepted in ADR-0012; D11 implemented | Revisit per phase |
| Hosting region (KVKK), vendors, e-invoice integrator (D16, D17) | Undecided | Before Phase 5 and 7 |

## Known limitations

- **Messaging:** email and SMS are sent synchronously inside transactions, through logging placeholders only. Phase 5 replaces this with an after-commit outbox; until then, no real provider (ADR-0011).
- **Web refresh cookie:** the web cookie variant of the refresh endpoint is not built; refresh tokens are sent in the body (ADR-0003).
- **Districts:** stored as free text; only the 81 provinces are reference data.
- **Concurrency tests:** they use MockMvc in one JVM, not real HTTP. The salon row lock has not been load-tested.
- **Availability ignores appointments** until Phase 4 adds them as absences. The public endpoint has no rate limit yet (Phase 8).
- **Overlapping working hours across branches** are prevented by the application under the salon row lock, not by a database constraint (ADR-0012).
- **Hours at a branch a staff member left** are kept (unassigning does not delete them; `salons` cannot depend on `scheduling`). They no longer produce availability, but they still count in the cross-branch overlap check, so new hours elsewhere can get `409 WORKING_HOURS_CONFLICT` naming the old branch. Clearing them (`PUT .../branches/{old}/working-hours` with no periods) is always allowed. Phase 4 can clean them up with a `salons` membership event if this proves confusing.
- **DST fall-back:** a local time that occurs twice is offered only at its earlier instant. Turkey has no DST, so this matters only for future markets.
- **Time format:** responses carry local times as `"09:00:00"`; requests accept `"09:00"`.

## Environment notes (this machine)

- JDK 25 is installed at user level, and `JAVA_HOME` points to it. Restart VS Code and terminals if `java -version` still shows 21.
- Docker Desktop must be running for tests (Testcontainers) and `spring-boot:run` (Compose). It is installed per user (`%LOCALAPPDATA%\Programs\DockerDesktop`).
- The GitHub CLI is not logged in, so CI results must be checked on GitHub (`aubreyzip/salon-backend`, Actions tab).
- CI: `mvnw` must stay executable in git (mode `100755`, fixed in `a90cce1`). Files created on Windows lose the bit: use `git update-index --chmod=+x <file>`.
