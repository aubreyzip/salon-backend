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

## Consequences

- Slightly more modeling up front (one extra level), but no painful migration later.
- Subscription plans can limit the number of branches and staff per salon.
- Public search, reviews, ratings and favorites target branches.
