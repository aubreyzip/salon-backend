# Project status

Last updated: 2026-11-03 (after commit `adc074b feat: phase 2`).

Roadmap: `docs/architecture/initial-architecture-report.md` §14. Binding decisions: `docs/adr/` (index in `docs/adr/README.md`).

## Phases

| Phase | Status | Commit |
|---|---|---|
| 0. Foundation | Done, except the deferred items below | `3429fc6` |
| 1. Identity (registration, login, JWT and refresh tokens, email and phone verification, password reset, audit) | Done | `3612321` |
| 2. Salons and access (salons, branches, staff and roles, invitations, reactivation, review lifecycle, tenant isolation, branch scopes) | Done (251 tests, randomized-order runs green) | `adc074b` |
| **3. Catalog and scheduling** | **Next. Not started** | — |
| 4. Booking | Planned | — |
| 5. Notifications (outbox, real providers) | Planned | — |
| 6. Discovery, favorites, reviews, media | Planned | — |
| 7. Billing (iyzico, e-invoice) | Planned | — |
| 8. Production hardening | Planned | — |

Deferred from Phase 0:
- `.editorconfig`, Spotless and JaCoCo plugins.
- Structured logging configuration and actuator exposure settings.
- `btree_gist` extension migration (add with booking, ADR-0005).
- Mockito agent configuration: Mockito currently self-attaches with a JDK warning.

## Phase 3 scope (from roadmap and ADRs)

- **Catalog:**
  - service categories and services (salon level, `CATALOG_MANAGE`);
  - per-branch offerings with price and duration overrides;
  - staff skills with overrides (staff → branch → service default).
- **Scheduling:**
  - staff working hours per branch, with effective-from/to;
  - time off and branch closures;
  - **branch opening hours** (deferred here from Phase 2, ADR-0008).
- **Availability engine:** a pure function with extensive unit tests (report §7). Branch time zone, slot grid, minimum notice and maximum advance come from the branch `BookingPolicy`.
- **Authorization:**
  - schedules use `TenantAccess.requireForStaffResource(... SCHEDULE_MANAGE_ANY, SCHEDULE_MANAGE_OWN)`;
  - catalog uses `require(... CATALOG_MANAGE)` (ADR-0010);
  - new endpoints go into `TenantIsolationIntegrationTests`.
- **Decide at the start of Phase 3:** whether inactive branches can have schedules or offerings.

## Open product decisions

| Topic | Current behavior | Recommendation / when |
|---|---|---|
| Client privacy for receptionists and staff | No client records yet | Branch-scoped roles see clients with appointments in their branches (Phase 4, ADR-0010) |
| Inactive branches | Staff can be assigned; the last active branch can be deactivated | Decide in Phase 3 |
| Branch-scoped managers | Managers are salon-wide | Keep, unless explicitly changed (ADR-0010) |
| Staff self-service profile | Staff cannot edit their own profile | Add a `/me` profile endpoint when clients need it |
| Editing a salon while `PENDING_REVIEW` | Allowed | Decide before launch (admin may review a moving target) |
| Auditing failed security attempts (e.g. wrong-account invitation use) | Not audited | Consider in Phase 8 |
| Report decisions D6–D19 (report §13) | Not yet relevant; D11 (onboarding review) is implemented | Revisit per phase |
| Hosting region (KVKK), vendors, e-invoice integrator (D16, D17) | Undecided | Before Phase 5 and 7 |

## Known limitations

- **Messaging:** email and SMS are sent synchronously inside transactions, through logging placeholders only. Phase 5 replaces this with an after-commit outbox; until then, no real provider (ADR-0011).
- **Web refresh cookie:** the web cookie variant of the refresh endpoint is not built; refresh tokens are sent in the body (ADR-0003).
- **Districts:** stored as free text; only the 81 provinces are reference data.
- **Concurrency tests:** they use MockMvc in one JVM, not real HTTP. The salon row lock has not been load-tested.

## Environment notes (this machine)

- JDK 25 is installed at user level, and `JAVA_HOME` points to it. Restart VS Code and terminals if `java -version` still shows 21.
- Docker Desktop must be running for tests (Testcontainers) and `spring-boot:run` (Compose).
- The GitHub CLI is not logged in, so CI results must be checked on GitHub (`aubreyzip/salon-backend`, Actions tab).
