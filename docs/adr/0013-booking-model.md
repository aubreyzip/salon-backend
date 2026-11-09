# ADR-0013: Booking model

- Status: Accepted (Phase 4 decision register, confirmed 2026-11-07: decisions 27–41; 28, 33 and 37 adjusted after review)
- Date: 2026-11-07

## Context

Phase 4 adds appointments: customers book online, salons book walk-ins and phone bookings, and both sides change appointments later. ADR-0005 (double booking), ADR-0007 (verified phone), ADR-0009 (confirmation mode) and ADR-0012 decisions 21–26 (schedule changes, customer limits, override) already constrain it. This register settles the rest before the code.

Principle from the review: an appointment is a commitment between a customer and a salon, not just a time slot. The backend keeps that commitment when schedules change, staff become unavailable, requests are retried or a branch closes.

## Decision

### Booking

| # | Topic | Decision |
|---|---|---|
| 27 | Customer endpoints | `POST /api/v1/me/appointments` (branch, services, optional staff member, start); list, read, cancel and reschedule under `/me/appointments`. Salon side under `/api/v1/salons/{salonId}/appointments` and `/clients`. |
| 28 | "Any available staff member" (adjusted) | Candidates are only staff the slot check finds free for the **exact** start: active, bookable, assigned to the branch, skilled in **every** requested service, within opening and working hours, without time off, and without a blocking appointment overlapping `[start, start + duration + buffer)`. Among them, the one with the fewest booked minutes that local day at the branch; ties go to the lowest staff id. If the exclusion constraint rejects the pick (a concurrent booking won), the next candidate is tried, at most 3 attempts in total, then `409 SLOT_UNAVAILABLE`. The response names the assigned staff member. |
| 29 | Customer cancel and reschedule | Allowed until the branch's `cancellation_cutoff_minutes` (default 120) before the start; after it `409 CANCELLATION_WINDOW_CLOSED`. A reschedule keeps the appointment row and changes the start, the staff member or both, under the normal customer rules (grid, notice, advance, availability). Services cannot change (cancel and book again). The salon may cancel or reschedule any future appointment at any time. |
| 30 | Reschedule with manual confirmation | A customer reschedule in a branch with `auto_confirm = false` sets the appointment back to `PENDING` with a new deadline. A salon reschedule keeps the status. |
| 31 | Pending expiry | Deadline = the earlier of booking time + `pending_confirmation_timeout_minutes` and the start. A job runs every minute and sets `EXPIRED`. |
| 32 | Confirm and reject | `APPOINTMENT_MANAGE_ANY` in the branch, or `APPOINTMENT_MANAGE_OWN` for one's own appointments. The rejection reason is optional. |
| 33 | Completed and no-show (adjusted) | **No automatic completion.** Staff (same permissions as 32) mark `COMPLETED` or `NO_SHOW` once the appointment has started. The mark is final. Unmarked past appointments stay `CONFIRMED`; the salon list filters them (`awaitingOutcome=true`). They block nothing, because only future appointments block. Reviews (Phase 6) are only for appointments a salon marked `COMPLETED`, so a no-show can never become a review. |
| 34 | Reasons and notes | Salon cancellation: reason required (3–500 characters), shown to the customer. Customer cancellation: reason optional. Rejection: reason optional, shown to the customer. Customer note (≤ 500), visible to the salon. Internal salon note (≤ 1000), never shown to the customer. |
| 37 | Salon-side booking (decision 20, adjusted) | Every service, including those not bookable online. No minimum notice; any whole minute (no grid); up to 15 minutes in the past (walk-ins); up to **365 days** ahead regardless of the branch's customer limit. It must still fit opening and working hours and be free. Created `CONFIRMED`. **Allowed in inactive branches** (pre-opening bookings) and in salons not yet approved; blocked only for suspended salons. Customers never book inactive branches (not public). A branch that closes for good with appointments goes through the decision 21 process (closures, override). |
| 40 | Who can book online | Active salon and active branch (billing entitlement follows in Phase 7). The customer account must be active and have a verified phone, otherwise `403 PHONE_NOT_VERIFIED` (ADR-0007). |
| 41 | `Idempotency-Key` | Required on every create and reschedule, customer and salon side (`400 IDEMPOTENCY_KEY_REQUIRED`). Scoped per user, kept 24 hours. Same key and same request: the original result is returned (the appointment in its current state). Same key with a different request or endpoint: `422 IDEMPOTENCY_KEY_REUSED`. Concurrent retries with one key never create two appointments (see Implementation). |

### Clients and privacy

| # | Topic | Decision |
|---|---|---|
| 35 | Salon clients (`salon_client`, report D8) | One record per salon and customer. Created automatically on a customer's first booking at the salon, linked to the account; linked records show the account's **current** name and phone. Staff create records for walk-ins and phone bookings: name required, phone optional and not unique. Staff cannot search platform accounts; a record becomes account-linked only through the customer's own booking. Merging duplicates comes later. Staff bookings for a linked client count toward decisions 23 and 24. |
| 36 | Client privacy (ADR-0010 open decision, report D15) | `CLIENT_VIEW` holders see the client list and client details. Branch-scoped holders (receptionists) see only clients with an appointment in one of their branches. Members without `CLIENT_VIEW` (stylists) see a client's name and phone only on appointments they may view (their own). |

### Schedule-change override and events

| # | Topic | Decision |
|---|---|---|
| 38 | Emergency override (decisions 21, 25, 26) | Every checked change accepts an optional `conflictOverride: {"reason": "..."}` (3–500 characters). It needs `APPOINTMENT_CONFLICT_OVERRIDE` in addition to the change's own permission (otherwise `403 INSUFFICIENT_PERMISSION`). The change goes through; the affected appointments keep their status and get a "needs attention" flag with the reason. The override is audited, and the flagged appointments are listed with `needsAttention=true`. The flag clears when the appointment is rescheduled, cancelled, or marked resolved by staff. Endpoints without a body (archive, deactivate, delete special day, remove offering) accept the same JSON as an optional body. |
| 39 | Notifications | Phase 4 publishes domain events with ids only (`AppointmentBooked`, `…Confirmed`, `…Rejected`, `…Cancelled`, `…Rescheduled`, `…Expired`, `…Completed`, `…NoShow`, `…NeedsAttention`) through the Modulith publication registry. No messages are sent until the Phase 5 outbox (ADR-0011). |

## Implementation (technical decisions)

- **Module:** `booking` (`com.salon.booking`), depending on `salons`, `catalog`, `scheduling`, `identity` and `audit`. Dependencies: `salons ← catalog ← scheduling ← booking`.
- **Extension point moved (decision 26):** `BlockingAppointments` and `BlockingAppointment` now live in the `salons` root, with the appointment's service ids, queries by service, a time-window query for availability, and `flagForAttention` for the override. `salons.AppointmentConflicts` holds the shared 409 and override logic for `salons`, `catalog` and `scheduling`.
- **Tables:** `appointment`, `appointment_item` (price, duration and name snapshot per service, ADR-0012), `appointment_history` (every transition), `salon_client`, `booking_customer_lock`, `idempotency_record`. Migration `booking/V2026_11_07_1000__create_booking.sql` also creates `btree_gist`.
- **Constraints (ADR-0005, decision 23):** `appointment_no_staff_overlap` over `tstzrange(start_at, block_end_at)` and `appointment_no_customer_overlap` over `tstzrange(start_at, end_at)` for `customer_user_id IS NOT NULL`, both only for `PENDING`/`CONFIRMED`. Violations map to `409 SLOT_UNAVAILABLE` and `409 CUSTOMER_OVERLAP`.
- **Lock order (no deadlocks):** idempotency key → customer lock row `FOR UPDATE` (decision 24) → salon row `FOR SHARE` (decision 21). Schedule and membership changes take only the salon row `FOR UPDATE`. Bookings do not block each other; they wait for schedule changes and the reverse.
- **Idempotency:** the key row is inserted first in the booking transaction. A concurrent retry waits on its primary key, then fails and returns the stored result. Failed requests roll back their key, so they can be retried.
- **Jobs:** `@Scheduled` (no Quartz, no ShedLock). Expiry selects due rows `FOR UPDATE SKIP LOCKED` in batches, so several instances never expire one appointment twice. Idempotency records older than 24 hours are deleted hourly.
- **Availability:** blocking appointments are absences in the engine. `scheduling.BookableSlots` checks one exact start for booking and rescheduling (the rescheduled appointment itself is ignored). There is a salon-side availability view (decision 20).
- **Deadlocks:** concurrent conflicting inserts can deadlock in the exclusion constraint check (ADR-0005). `BookingExecutor` retries such an attempt (up to 3 times, separate from the 3 staff picks of decision 28).
- **Reschedules** keep the booked prices (the commitment) and take the new staff member's durations and the current buffer.
- **Customer view** never contains the internal note, the attention flag or other clients. `changeableUntil` tells clients the cutoff.
- **Tests:** `TestBlockingAppointments` stays as a second extension-point source in integration tests (appointments at arbitrary times). Booking tests use the `BookableSalon` fixture.

## Consequences

- Appointments now actually block schedule, staff and catalog changes (decisions 21 and 26), unless overridden.
- `SalonPermission.APPOINTMENT_CONFLICT_OVERRIDE` is new (additive in v1; clients must tolerate unknown values).
- Past unmarked appointments accumulate as `CONFIRMED` if salons never record outcomes. The `awaitingOutcome` filter makes them visible; reminders follow with notifications (Phase 5).
- Bookings in an inactive branch are visible only to the salon until the branch is activated.
- A `DELETE` or bodiless `POST` with an optional JSON body is unusual; clients that cannot send it can't override those four changes. Revisit if a client needs a query parameter instead.
- Duplicate walk-in records and account-linked records are not merged yet.
- Walk-in client records cannot be edited yet. Only creating, listing and reading exist. ADR-0014 decision 54 adds `CLIENT_MANAGE`.
- The salon may cancel a past, still `CONFIRMED` appointment, which deviates from decision 29 ("future"). ADR-0014 decision 55 restricts it to before the start; afterwards staff record `NO_SHOW` or `COMPLETED`. Salon reschedules are blocked after the start too (decision 64).
- Events carry ids only. ADR-0014 decision 56 adds the history entry id, so notifications describe the state at the time of the change.
- Moving an appointment to another branch is not supported. Cancel and book again.
- While building this, a Phase 3 defect was found and fixed: `time` values were shifted by the JVM's offset (ADR-0006).
