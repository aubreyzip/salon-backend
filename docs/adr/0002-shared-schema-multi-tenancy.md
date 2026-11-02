# ADR-0002: Shared-schema multi-tenancy with a `salon_id` discriminator

- Status: Accepted
- Date: 2026-11-02

## Context

The tenant is a salon business (which may have several branches). The product is also a marketplace: customers book across many salons, and public discovery reads across tenants. We expect thousands of small tenants.

## Decision

- One database and one schema. Every tenant-owned table has a non-null `salon_id`, including child tables.
- Same-tenant references are enforced by composite foreign keys, e.g. `appointment(salon_id, staff_id)` → `staff_member(salon_id, id)`.
- Management APIs carry the tenant explicitly in the path: `/api/v1/salons/{salonId}/...`. One authorization component (`TenantAccess`) checks the caller's membership and permission for that salon before a use case runs. Tenant-scoped repository methods always take `salonId`.
- Resources of another tenant return 404, not 403.
- Every management endpoint is covered by a tenant-isolation test (an outsider, a staff member with only own-resource rights, a customer, an anonymous caller).
- PostgreSQL Row-Level Security is deferred, but the design above keeps it possible.

Rejected: database per tenant and schema per tenant. Both mean migration fan-out across thousands of tenants and make cross-tenant marketplace reads difficult.

## Implementation status (Phase 2, 2026-11-03)

- `TenantAccess` (module `salons`) is the gate. `staff_member` is the membership table (one active membership per user and salon).
- Composite keys exist for `branch`, `staff_member`, `staff_branch` and `staff_invitation`. `SalonDatabaseConstraintsIntegrationTests` proves with direct SQL that the database rejects cross-salon references (and checks the other salon constraints by name) even when application checks are bypassed.
- `TenantIsolationIntegrationTests` runs every salon management endpoint as owner, manager, stylist, owner of another salon, and anonymous.
- Platform admins do not get implicit tenant access. They use `/api/v1/admin/**` endpoints, which are audited.
- Salon-wide invariants are protected by a row lock on the salon (`SELECT … FOR UPDATE`). Every membership change locks it before checking access, so changes in one salon are serialized. This fixed a race in which two owners demoting each other at the same time could leave a salon with no owner (`OwnershipConcurrencyIntegrationTests`). Access is checked before the suspension status, so outsiders cannot learn that a salon is suspended.
- Branch-level scope on top of tenant access: ADR-0010.

## Consequences

- Cross-tenant data leakage is the main risk. It is mitigated by explicit tenant parameters, database constraints and tests, not by hidden filters.
- Cross-tenant reads (a customer's own appointments, public search) are straightforward.
- Backup and restore are per database, not per tenant. Per-tenant export needs explicit tooling.
