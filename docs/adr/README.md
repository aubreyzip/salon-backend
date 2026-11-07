# Architecture Decision Records

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith-with-spring-modulith.md) | Modular monolith enforced by Spring Modulith | Accepted |
| [0002](0002-shared-schema-multi-tenancy.md) | Shared-schema multi-tenancy with `salon_id`; salon row lock for salon-wide invariants | Accepted, implemented (Phase 2) |
| [0003](0003-self-issued-jwt-authentication.md) | Self-issued JWT access tokens, rotating refresh tokens | Accepted, implemented (Phase 1) |
| [0004](0004-flyway-for-schema-migrations.md) | Flyway with plain SQL owns the schema | Accepted, in use |
| [0005](0005-database-enforced-double-booking-prevention.md) | Double booking prevented by a PostgreSQL exclusion constraint | Accepted, implemented (Phase 4) |
| [0006](0006-time-and-money-representation.md) | Time and money representation | Accepted (JDBC time-zone setting corrected 2026-11-07) |
| [0007](0007-customer-sign-in-and-phone-verification.md) | Email + password sign-in, verified phone before booking | Accepted, implemented (Phase 1) |
| [0008](0008-salon-and-branch-tenant-model.md) | Salons have branches from day one | Accepted, implemented (Phase 2) |
| [0009](0009-booking-confirmation-mode.md) | Per-branch booking confirmation, automatic by default | Accepted, implemented (Phase 4) |
| [0010](0010-permission-scopes.md) | Permission scopes: salon-wide, assigned branches, own resources | Accepted, implemented (Phase 2) |
| [0011](0011-transactional-messaging.md) | Messages delivered after commit through a transactional outbox | Accepted, implement in Phase 5 |
| [0012](0012-catalog-and-scheduling-model.md) | Catalog, working hours, branch calendars and the availability engine; appointment rules for schedule changes, inactive branches and customer limits (decisions 21–26) | Accepted, implemented (Phase 3 and 4) |
| [0013](0013-booking-model.md) | Booking: endpoints, staff choice, cancellation and rescheduling, expiry, outcomes, clients and privacy, salon bookings, override, events, idempotency (decisions 27–41) | Accepted, implemented (Phase 4) |

New ADRs: next number, same structure (Status, Date, Context, Decision, Consequences). When code changes a decision, update the ADR in the same change.
