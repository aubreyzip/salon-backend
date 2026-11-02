# ADR-0008: Salons have branches from day one

- Status: Accepted (product decision D4, confirmed 2026-11-02)
- Date: 2026-11-02

## Context

Some businesses operate several locations. Retrofitting branches after launch would mean migrating appointments, schedules, staff assignments and service offerings. Stylists sometimes work at different locations on different days.

## Decision

- **Salon** = the tenant: the business, its staff memberships, service catalog and subscription. Its id is the `salon_id` tenant discriminator (ADR-0002).
- **Branch** = a physical location of a salon: address, coordinates, IANA time zone, opening hours, booking policy. Customers discover and book at the branch level.
- Every salon has at least one branch. Single-location salons have exactly one, and clients may hide the branch concept for them.
- Staff belong to the salon and are assigned to one or more branches (`staff_branch`). Working hours are recorded per branch.
- Services are defined per salon. Branches choose which services they offer, optionally overriding price and duration. Staff skills may override them again (staff → branch → service default).
- Appointments reference both `salon_id` and `branch_id`, with composite foreign keys ensuring the branch belongs to the salon.

## Implementation status (Phase 2, 2026-11-03)

Implemented in module `salons`:
- Salon lifecycle: `DRAFT → PENDING_REVIEW → ACTIVE ⇄ SUSPENDED`. Rejection returns to `DRAFT` with a note. Submission requires an active branch with map coordinates.
- Branches: structured address with `province_id` (81 provinces seeded, plate codes), district as text, coordinates, IANA time zone, booking policy.
- Staff: role, profile, bookable flag, branch assignments, email-bound invitations (7 days).
- Staff lifecycle: deactivate and **reactivate** (same membership row: role, branch assignments and account link are kept; invitations revoked by deactivation stay revoked; same rank rules; audited `salons.staff.reactivated`). A former member who accepts a new invitation gets `409 FORMER_MEMBER` and must be reactivated instead, so a new invitation can't become a way back in with a different role.
- Invitations can be **cancelled** explicitly (`DELETE /salons/{id}/staff/{staffId}/invitations`, `STAFF_MANAGE` + rank rules, audited `salons.staff.invitation_cancelled`) without deactivating the staff profile. Deactivation also revokes open invitations, but it removes the person from scheduling, so it is not a substitute.
- Branch assignments limit where receptionists and staff work (ADR-0010).

Deferred:
- Branch opening hours move to the scheduling phase, together with staff working hours.
- Districts are free text until an official district list is imported as reference data.
- Services and their per-branch and per-staff overrides belong to the catalog phase.

## Consequences

- Slightly more modeling up front (one extra level), but no painful migration later.
- Subscription plans can limit the number of branches and staff per salon.
- Public search, reviews, ratings and favorites target branches.
