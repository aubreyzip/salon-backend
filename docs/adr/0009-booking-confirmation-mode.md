# ADR-0009: Per-branch booking confirmation, automatic by default

- Status: Accepted (product decision D5, confirmed 2026-11-02)
- Date: 2026-11-02

## Context

Customers expect instant confirmation. Some salons want to approve bookings manually (e.g. for long or expensive services, or new customers).

## Decision

- Each branch has a booking-policy setting `auto_confirm`, **default `true`**.
- With `auto_confirm = true`, a valid booking is created directly as `CONFIRMED`.
- With `auto_confirm = false`, a booking is created as `PENDING`. A pending booking **holds the slot**, because `PENDING` is a blocking status in the double-booking exclusion constraint (ADR-0005). Staff with the right permission can confirm or reject it.
- Unconfirmed `PENDING` bookings **expire** after a configurable deadline (with a reasonable default) and release the slot. Expiry is done by a scheduled job and notifies the customer.
- Every status change is recorded in the appointment history and triggers notifications through domain events.

## Consequences

- Clients must display `PENDING` clearly and handle `REJECTED` and `EXPIRED` outcomes.
- A scheduled expiry job is needed in the booking phase. With more than one instance it must run exactly once (database job table with `SKIP LOCKED`, or ShedLock).
