# ADR-0011: Messages are delivered after commit through a transactional outbox (Phase 5)

- Status: Accepted (current limitations documented; outbox to be implemented in Phase 5). ADR-0014 (accepted) details the Phase 5 design: two stages, with the listener writing notifications and a delivery worker calling providers outside transactions.
- Date: 2026-11-03

## Context: current behavior (Phases 1–2)

Email and SMS (`EmailGateway`, `SmsGateway` in module `messaging`) are called **synchronously inside the business transaction**. This covers verification codes, password reset codes, phone OTPs and staff invitations. Only logging placeholders exist.

Verified by tests:
- If sending fails, the exception rolls back the whole transaction: no staff member, no invitation, no audit row. An earlier invitation stays valid because its revocation is rolled back too (`SalonAuditAndMessagingIntegrationTests`). The caller gets `500 INTERNAL_ERROR`.

Limitations:
1. **Sent, then rolled back.** If the message goes out and the transaction then fails (constraint or optimistic-lock conflict at flush/commit, connection loss, timeout), the recipient holds a code or invitation token that does not exist.
2. **Provider latency inside the transaction.** A real provider call would hold a database connection open, and for membership changes also the salon row lock (ADR-0002 implementation notes). That would serialize all membership changes of a salon behind the provider.
3. **No retries.** A transient provider failure fails the user's request.
4. **Unspecific error.** Provider failures surface as a generic 500.

**Rule until Phase 5:** only non-blocking placeholder gateways are allowed. A real provider must not be plugged into the synchronous path.

## Decision (Phase 5)

Use a **transactional outbox** based on the Spring Modulith event publication registry that is already in place (`spring-modulith-starter-jpa`, `event_publication` table created by Flyway).

1. **Business transactions publish events, not messages.** For example `StaffInvitationIssued(invitationId, salonId)` or `VerificationCodeRequested(challengeId)`, published with `ApplicationEventPublisher`. The registry stores the publication in the same transaction as the business data: if the transaction rolls back, nothing is published and nothing is sent.
2. **Delivery happens after commit.** `@ApplicationModuleListener` handlers in module `notification` run after commit, asynchronously, in their own transaction. They load current state and skip deliveries that no longer apply (e.g. an invitation cancelled before delivery).
3. **Secrets are minted at delivery time and never stored in plain text.** Events carry ids only, because serialized events are stored in `event_publication`. The listener generates the invitation token or OTP, stores only its hash, commits, then sends.
   - If sending fails, the retry mints a new secret and replaces the hash, so only a delivered secret can ever work.
   - If the listener's own commit fails, nothing is sent.
   - Schema change in Phase 5: `staff_invitation.token_hash` and `verification_challenge.code_hash` become nullable until delivery, plus a delivery status.
4. **At-least-once delivery, with idempotency.** A `notification` record per (source event, channel, recipient) with a unique key and the provider message id. Failed publications are retried (resubmission of incomplete publications on startup and on a schedule, with backoff). Publications that keep failing are marked failed and alerted on (Modulith staleness monitoring) [verify exact property names in the Modulith 2.1 docs when implementing].
5. **API behavior.** Requests that trigger messages answer `202 Accepted` without waiting for the provider. Users can request a resend (existing endpoints). Provider outages no longer fail business operations.

Alternatives rejected:
- **Sending in `afterCommit` (TransactionSynchronization):** avoids messages for rolled-back transactions, but loses messages on crash or provider error and has no retry. Acceptable at most for non-critical notifications.
- **A message broker:** unnecessary in a modular monolith (ADR-0001).
- **A custom outbox table:** equivalent to the registry already present.

## Consequences

- Delivery is decoupled from request latency and from the salon row lock.
- The secret-at-delivery rule keeps plaintext codes and tokens out of the database, including the outbox.
- Rate limits stay on request time (they count challenge and invitation rows).
- Phase 5 tests must cover:
  - a rolled-back transaction sends nothing;
  - a provider failure is retried and the earlier secret is invalidated;
  - a duplicate delivery produces one message;
  - an invitation cancelled before delivery is not sent.
