# ADR-0009: Per-branch booking confirmation, automatic by default

- Status: Accepted (product decision D5, confirmed 2026-11-02), implemented (Phase 4, 2026-11-07)
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

## Implementation (Phase 4, ADR-0013)

- `branch.auto_confirm` (default true) and `pending_confirmation_timeout_minutes` (default 120) already existed. The deadline is the earlier of booking time + timeout and the start (decision 31).
- Confirm and reject: `APPOINTMENT_MANAGE_ANY` in the branch, or `_OWN` for one's own appointments (decision 32). A passed deadline cannot be confirmed (`409 INVALID_STATUS_TRANSITION`).
- A customer reschedule in a branch with manual confirmation goes back to `PENDING` (decision 30).
- Expiry: `BookingJobs.expireDuePendingAppointments`, every minute (`BookingScheduler`). It selects due rows `FOR UPDATE SKIP LOCKED` in batches of 100, so it is safe on several instances without ShedLock.
- History: `appointment_history`. Events: `booking.AppointmentEvent` (ids only); notifications follow in Phase 5.
