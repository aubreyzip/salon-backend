# ADR-0001: Modular monolith enforced by Spring Modulith

- Status: Accepted
- Date: 2026-11-02

## Context

Salon is a new multi-tenant SaaS with an evolving domain (identity, salons and branches, catalog, scheduling, booking, notifications, billing). The team is small and there is no proven need for independent deployment or scaling of parts of the system. Microservices would add network failure modes, distributed transactions and operational cost before the domain boundaries are even stable.

## Decision

- One deployable Spring Boot application and one PostgreSQL database.
- Each business module is a direct sub-package of `com.salon` (e.g. `com.salon.booking`). Only types in the module's root package (or a `@NamedInterface`) are visible to other modules. Sub-packages are internal.
- `ModularityTests` (`ApplicationModules.verify()`) runs in every build and fails on dependency cycles or on access to another module's internals.
- Modules reference each other's data by ID only (no JPA associations across modules).
- Side effects across modules use domain events consumed by `@ApplicationModuleListener`, persisted by the Modulith event publication registry (`event_publication` table, owned by Flyway). No message broker.
- Inside a module: light layering (`web`, `application`, `domain`, `infrastructure`). Ports and adapters only at real external boundaries (payment, SMS, email, push, storage).

Initial modules: `identity`, `salon` (tenant, branches, staff/membership), `catalog`, `scheduling`, `booking`, `customer`, `review`, `discovery` (read-only), `billing`, `notification`, `media`, `audit`, `messaging`, `shared` (kept small).

`messaging` (added in Phase 1) contains only channel gateways (`EmailGateway`, `SmsGateway`, later push) and depends on no business module. `identity` uses it directly to send one-time codes synchronously. `notification` will use it for event-driven messages. Keeping the channels separate avoids an `identity` ↔ `notification` dependency cycle.

## Consequences

- Simple deployment, local development and transactions. Refactoring across modules is still cheap.
- Boundaries are enforced by a test, not by convention, so they stay real and a module can be extracted later if a measured need appears.
- Event listeners must be idempotent (at-least-once delivery).
- The `discovery` module is a documented exception: it may run read-only SQL across other modules' tables.
