# ADR-0005: Double booking is prevented by a PostgreSQL exclusion constraint

- Status: Accepted (implementation in the booking phase)
- Date: 2026-11-02

## Context

Two customers (or a customer and a receptionist) can try to book the same employee for overlapping times at the same moment, from several application instances. Application-level "check then insert" is racy. Pessimistic locking and `SERIALIZABLE` add complexity and contention.

## Decision

- Each appointment stores `start_at`, `end_at` and `block_end_at` (end plus buffer) as `timestamptz`.
- The `appointment` table has an exclusion constraint (requires the `btree_gist` extension) that forbids overlapping `tstzrange(start_at, block_end_at, '[)')` for the same `staff_id` while the status is blocking (`PENDING`, `CONFIRMED`).
- A second exclusion constraint does the same for the **customer** (ADR-0012, decision 23). It covers account-linked customers (`customer_user_id IS NOT NULL`) over `tstzrange(start_at, end_at, '[)')`, without the staff buffer, platform-wide (no `salon_id` in the key). Unlinked walk-in records are not covered.
- The application still checks availability first to give a friendly error, but the constraint is the final arbiter. A violation (SQLSTATE `23P01`) is mapped to HTTP 409 with code `SLOT_UNAVAILABLE`.
- Appointment creation requires an `Idempotency-Key` header; retries return the original result.
- State transitions use optimistic locking (`@Version`).
- Acceptance test: N concurrent booking attempts for one slot result in exactly one success.

## Consequences

- The guarantee holds regardless of instance count, thread scheduling or application bugs, at the default `READ COMMITTED` isolation.
- Half-open ranges allow back-to-back appointments.
- The managed PostgreSQL provider must support `btree_gist`.
- A migration test must confirm the constraint expression is accepted (it must be immutable). If not, fall back to a stored `tstzrange` column maintained by the application.
