# ADR-0014: Notifications (Phase 5 decision register)

- Status: **Proposed**. Decisions 42–56 come from the 2026-11-08 review. The technical design and the questions at the end wait for review; nothing here is implemented yet.
- Date: 2026-11-08

## Context

Phase 4 publishes `booking.AppointmentEvent`s, but nothing listens to them yet (ADR-0013 decision 39). Email and SMS are still sent synchronously inside business transactions, through logging placeholders (ADR-0011). Phase 5 delivers notifications: after commit, retried, idempotent, tracked, and without plugging a real provider into the business transaction.

The provider and hosting choices (report D16, D17) are not made yet. They must not block provider-independent work.

## Decision

### Infrastructure and providers

| # | Topic | Decision |
|---|---|---|
| 42 | Hosting region (D16) | Prefer Turkey-based hosting for the primary application and database where technically and commercially practical. KVKK is not assumed to forbid every international transfer. An international provider can be considered after reviewing the transfer mechanism, contracts, subprocessors and data flows. This does not block Phase 5 development. |
| 43 | Providers (D17) | Provider-independent: ports with placeholder adapters only (`EmailGateway`, `SmsGateway`, a new `PushGateway`). Production providers are chosen after technical and legal due diligence. FCM is a candidate, not an approved provider. |

### Who gets what, and how

| # | Topic | Decision |
|---|---|---|
| 44 | Recipients | See the matrix below. Customers also get a confirmation of their own cancellations and reschedules. Salon recipients are resolved from branch-level permissions and the appointment's assignment at the time of the change. |
| 45 | Channels | **Persistent in-app notifications are the source of truth**: every notification exists in-app, whatever else is sent. Push is the primary external channel. SMS is used only for explicitly listed high-priority and fallback cases (proposal below). Email is used for account messages only. A push submitted to the provider is recorded as *submitted*, never as *delivered* or *read*. |
| 46 | Customer reminders | One reminder 24 hours before a **confirmed** appointment. Skipped when the appointment was booked (or last rescheduled) less than 24 hours before its start. Recalculated after a reschedule; cancelled after cancellation, rejection or expiry. It follows branch-local time and the customer's reminder preferences (decision 49). |
| 47 | Walk-in clients | No automated notifications to walk-in clients (records without an account) in v1. |
| 48 | Marketing and İYS | No marketing messages in v1. Appointment and account messages are treated as transactional, **subject to legal verification**. Marketing and transactional templates are kept separate. Each template declares a category, and its legal basis is documented in the template table (see Implementation). |
| 49 | Preferences | Customers set reminder preferences per external channel (push, SMS). Mandatory transactional notifications (decision 44) cannot be turned off and are stored apart from optional reminders. Marketing has no preference because it does not exist in v1. In-app notifications are always created. |
| 50 | Quiet hours | Reminders and non-urgent messages are sent only between 09:00 and 21:00, branch-local time. Urgent appointment changes are handled explicitly (proposal below). |
| 51 | Language | Turkish only in v1. Templates are localization-ready (message key + locale). Accounts get a preferred-language field, defaulting to `tr`. |
| 52 | Staff outcome reminders | When an appointment has ended plus a grace period and no outcome is recorded (decision 33), the assigned staff member gets an **outstanding task**. The task closes when `COMPLETED` or `NO_SHOW` is recorded. Branch managers get a **daily summary** of their branch's outstanding outcomes. |
| 53 | Push devices | A user may register several devices. Each registration carries an **application id**, so a later separate staff app needs no redesign. |

### Phase 4 follow-ups

| # | Topic | Decision |
|---|---|---|
| 54 | Editing walk-in client records | `CLIENT_VIEW` stays read-only. A new permission `CLIENT_MANAGE` allows editing walk-in records (name, phone), with the branch rule of decision 36: branch-scoped holders may edit only clients with an appointment in their branches. Account-linked records are not editable (they show account data). Edits are audited without names or phones. `CLIENT_MANAGE` is additive in v1. |
| 55 | Salon cancelling past appointments | Decision 29 applies strictly: the salon cancels only **before the scheduled start**. Afterwards, staff record the outcome (decision 33). Currently the code allows cancelling until an outcome is recorded; Phase 5 fixes this, proving it with a failing test first. |

### Event contracts

| # | Topic | Decision |
|---|---|---|
| 56 | Notifications describe the state at the time of the change | Each `AppointmentEvent` gains the id of the appointment history entry written in the same transaction (`historyEntryId`). Consumers build appointment messages from that immutable entry, never from the current appointment row. The history entry gains the missing facts (see the review below). |

#### Review of the Phase 4 event contracts (requested before any consumer exists)

The events carry `appointmentId`, `salonId`, `branchId` and `customerUserId` (plus `byCustomer` on `Cancelled`). A consumer that loads the **current** appointment would produce wrong historical notifications:

- **Superseded state.** `Confirmed` handled after a later reschedule or cancellation would announce the new time, or a cancelled appointment as confirmed.
- **Repeated reschedules.** Two quick reschedules would both announce the final time.
- **Staff changes.** A reschedule to another staff member cannot tell the *previous* staff member that the appointment left their book.
- **Reasons.** Reasons shown to the customer (decision 34) are only on the appointment row, which a later change can overwrite.

`AppointmentRecorder` already writes an `appointment_history` row in the same transaction as every change. The row stores the action, the from and to statuses, the actor, the reason, `start_at`, `staff_id` and `occurred_at`. It is therefore the right snapshot, with two gaps:

- **No end time.** `end_at` is missing, and a reschedule can change it (the new staff member's durations).
- **No previous values.** The previous start, end and staff member are missing. They can only be derived from the preceding entry, which is fragile when several entries share a transaction.

**Change:**
- A new migration adds the nullable columns `end_at`, `previous_start_at`, `previous_end_at` and `previous_staff_id` to `appointment_history`. The Phase 4 migration is not edited, and old rows keep nulls.
- History entries stay append-only.
- Events carry `historyEntryId`. They stay small and keep personal data (reasons, names) out of `event_publication`.

This is safe now: the publication registry stores a publication per listener, and Phase 4 has none, so no serialized Phase 4 event exists that a new shape must read. This is to be confirmed by a test against a database migrated from Phase 4. From Phase 5 on, event changes are additive only, because stored publications must still deserialize.

What may be read as current state:
- branch facts (name, time zone);
- current memberships, used to decide who may still receive something;
- the reminder check at send time, because a reminder is about the present.

#### Recipient matrix (decision 44)

"Desk" means active members holding `APPOINTMENT_MANAGE_ANY` for the branch: owners, managers and receptionists assigned to it. "Staff" means the assigned staff member in the history entry, if they are still an active member with an account. The member who made a salon-side change is not notified of their own action. An account-linked customer is notified only while their account is active.

| Change | Customer | Assigned staff | Desk |
|---|---|---|---|
| Booked by customer, `PENDING` | Request received | — | Confirmation needed (action) |
| Booked by customer, `CONFIRMED` | Booking confirmed | New booking | — |
| Booked by salon (linked customer) | Salon booked you | New booking | — |
| Confirmed / Rejected (with reason) | Yes | — | — |
| Expired | Yes | — | — |
| Cancelled by customer | Confirmation of own action | Yes | — |
| Cancelled by salon (with reason) | Yes | Yes | — |
| Rescheduled by customer | Confirmation of own action | Old and new staff member | If back to `PENDING`: confirmation needed |
| Rescheduled by salon | Yes | Old and new staff member | — |
| Needs attention (override) | — (never shown to customers, ADR-0013) | — | Yes |
| Completed / No-show | — (review invitations are Phase 6) | — | — |

## Implementation (technical design, for review)

**Module.** `notification` (`com.salon.notification`), listening to `booking` events and later identity and invitation events. It depends on `booking`, `salons`, `identity` and `messaging`; nothing depends on it. `messaging` keeps the provider ports and placeholder adapters. `salons` gains a root query for members holding a permission in a branch, for recipient resolution.

**Atomicity: the outbox (ADR-0011).** The business change, its history entry and the `event_publication` row are written in one transaction. The Modulith JPA registry stores one publication per `@ApplicationModuleListener` inside the publishing transaction, so a rollback leaves nothing to deliver.

**Stage 1: the listener (after commit, asynchronous, own transaction, no network calls).**
- The listener resolves recipients and channels.
- It creates `notification` rows (in-app, per user) and `notification_delivery` rows (one per external channel, `PENDING`, with a due time that respects quiet hours).
- It reconciles reminders and tasks.
- **Idempotent:** a unique key (source history entry, recipient, kind) makes a redelivered event a no-op.
- Its commit completes the publication.

**Stage 2: the delivery worker (provider calls outside any transaction).** A scheduled job runs three steps:
1. It claims due deliveries with `FOR UPDATE SKIP LOCKED`. A short transaction marks them `SENDING` with a lease.
2. It calls the provider without a transaction.
3. It records the result in a new short transaction: `SUBMITTED` with the provider message id, or `FAILED` with an attempt count and the next attempt time.

Further rules:
- **Crash or lease expiry:** the delivery is retried (at-least-once). The delivery id is passed as the provider's idempotency key where the provider supports one.
- **Several instances:** the `SKIP LOCKED` claim lets them run without double sends.

**Retries.**
- **Deliveries:** exponential backoff. Proposal: 1 min, 5 min, 30 min, 2 h, 6 h; then `FAILED`, logged and counted.
- **Lapsed messages:** a delivery whose message no longer makes sense (for example a reminder for an appointment that has started) becomes `SKIPPED`.
- **Listener failures:** incomplete publications are resubmitted on restart and on a schedule through Modulith (`IncompleteEventPublications`, `spring.modulith.events.republish-outstanding-events-on-restart`, `spring.modulith.events.staleness.*`). These names exist in Modulith 2.1.1; their exact behavior is to be verified when implementing.
- **Completed publications:** purged after a retention period.

**Delivery tracking.**
- **Statuses:** `PENDING`, `SENDING`, `SUBMITTED`, `DELIVERED` (only from a provider receipt, e.g. an SMS delivery report), `FAILED`, `SKIPPED`.
- **Read state:** `read_at` exists only for in-app notifications, set by the user.
- **KVKK:** deliveries store the user id and a masked destination. The phone number or device token is resolved at send time.

**Ordering.** Asynchronous listeners do not guarantee per-appointment order. Each message states its own facts ("confirmed for 10:00", then "cancelled"), and the worker sends an appointment's deliveries in history order: a later one waits while an earlier one is still pending.

**Reminders (decision 46).**
- **Model:** one `reminder` row per appointment and kind: due time, the start it was computed for, and a status (`SCHEDULED`, `SENT`, `CANCELLED`, `SKIPPED`).
- **Reconciliation:** on booked, confirmed, rescheduled, cancelled, rejected and expired events, the listener reconciles the row from the **current** appointment. That makes it correct whatever order the events arrive in.
- **Send-time check:** a scheduled job sends due reminders only if, at that moment, the appointment is still `CONFIRMED`, the start is unchanged and the preference is on.

**Staff tasks (decision 52).** A scheduled job creates an outstanding task for each `CONFIRMED` appointment whose end plus the grace period has passed. Completed and No-show events close it. The daily summary runs per branch, in branch-local time.

**Existing messages move to the outbox (ADR-0011).** Verification codes, password reset codes, phone OTPs and invitations move to the outbox, with secrets minted at delivery time. The HTTP status codes stay as they are.

**API (additive).**
- `GET /me/notifications` (paged, with an unread count), `POST /me/notifications/{id}/read`.
- `GET`/`PUT /me/notification-preferences`.
- `PUT /me/devices` (register or refresh: application id, platform, token), `DELETE /me/devices/{id}`. A token is unique per application id. Re-registering it moves it to the signed-in user, which covers shared devices. Tokens the provider reports as invalid are deactivated.
- Staff tasks appear in their in-app notifications.

**Templates.**
- **In code:** templates are resource bundles (`notification/messages_tr.properties`), versioned with the application.
- **Template table:** each template declares a category (`ACCOUNT`, `APPOINTMENT`, `REMINDER`, `TASK`) and the legal basis to be confirmed by counsel. The working assumption is performance of the contract, KVKK article 5(2)(c), for account and appointment messages.
- **Marketing:** no marketing category exists in v1.

**Tests (in addition to ADR-0011's list).**
- **Outbox:** a rolled-back change creates no publication and no notification. A redelivered event creates nothing twice.
- **Historical state:** confirm then reschedule, handled late, still says "confirmed at the old time". Two reschedules produce two correct messages.
- **Recipients:** respect branch scope and deactivation, and actors are never notified of their own actions.
- **Reminders:** booking under 24 hours ahead, reschedule, cancellation, quiet hours, preferences off. Covered both in event order and out of order.
- **Delivery:** retries and backoff, lease expiry, two workers in parallel (`Concurrently.run`), provider failure, push recorded as `SUBMITTED` only.
- **Decision 55:** the salon cannot cancel after the start, proven with a failing test first.
- **Decision 54:** permission tests and new cases in `TenantIsolationIntegrationTests`.

## Questions for review (proposals; not decided)

1. **SMS cases (decision 45).** Proposal:
   - Always SMS, in addition to push: the salon cancels, or the salon reschedules.
   - SMS fallback when the customer has no active push device: booking confirmed, booking rejected, booking expired, and the reminder (if SMS reminders are on).
   - No SMS: confirmations of the customer's own actions, and all staff and desk notifications (in-app and push only).
2. **Default reminder preferences (decision 49).** Proposal: push on. SMS on only as a fallback for customers without a push device.
3. **Urgent changes at night (decision 50).** Proposal:
   - Transactional appointment notifications are always created and pushed immediately.
   - An SMS during quiet hours goes out immediately only if the appointment starts before 12:00 the next day; otherwise it waits until 09:00.
4. **A reminder due inside quiet hours (decisions 46, 50).** Proposal: move it to the last allowed time before the due time (e.g. 21:00 the evening before). If that is earlier than the booking, use the first allowed time after it.
5. **Grace period and summary time (decision 52).** Proposal: the task appears 2 hours after the appointment's end; the summary goes out at 09:00 branch time.
6. **Who counts as a "branch manager" for the summary.** We check permissions, not roles. Proposal: desk members (`APPOINTMENT_MANAGE_ANY` for the branch), which includes receptionists. The alternative is only salon-wide holders (owners and managers).
7. **`CLIENT_MANAGE` roles (decision 54).** Proposal: owners, managers and receptionists. Should creating walk-in clients, today `APPOINTMENT_MANAGE_ANY` or `_OWN` so stylists can book a walk-in for themselves, stay as it is?
8. **Decision 55 and salon reschedules.** Decision 29 says "any future appointment" for both cancelling and rescheduling. Proposal: also block salon reschedules after the start.
9. **Retention (KVKK).** Proposal: in-app notifications kept 12 months, the delivery log 90 days, completed event publications 7 days.

## Consequences

- Every appointment change produces durable in-app notifications, even while no external provider exists.
- `appointment_history` becomes the source for notification content. Its rows must never be updated.
- Event records change shape once now, safely, and stay additive afterwards.
- External delivery is decoupled from business transactions, row locks and provider outages. Delivery is at-least-once: a provider without idempotency support can rarely send twice.
- "Submitted" is the strongest push status the backend can claim. Read receipts exist only in-app.
- Real delivery waits for provider due diligence (decisions 42, 43). Until then, the placeholders log masked destinations only.
