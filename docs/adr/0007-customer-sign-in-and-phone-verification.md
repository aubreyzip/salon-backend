# ADR-0007: Customer sign-in with email and password, plus a verified phone before booking

- Status: Accepted (product decision D3, confirmed 2026-11-02)
- Date: 2026-11-02

## Context

Customers need an account to book online. Salons need a reachable phone number for every appointment, and unverified contact details lead to fake bookings and no-shows. Phone-only OTP login is popular in Turkey, but every login then costs an SMS and the endpoint becomes a target for SMS-pumping fraud.

## Decision

- Customers register and sign in with **email + password**. Email ownership is verified by a link or code.
- A **phone number (E.164) verified by SMS OTP is required before the first booking**, not at registration.
- OTP challenges are stored hashed with expiry, attempt counters and per-number/per-IP send limits. Initially only `+90` numbers are accepted for OTP.
- Login, registration and password-reset responses do not reveal whether an account exists.
- Phone-only OTP login is a possible later addition. It must not require schema changes: `user_account` already holds `phone_e164` and `phone_verified_at`, and `verification_challenge` supports a `LOGIN_OTP` purpose.

## Consequences

- An SMS provider is needed in Phase 1. Until one is chosen, a logging stub adapter is used outside production.
- Booking endpoints must reject customers without a verified phone (clear error code so clients can start verification).
- Users get a little more friction at first booking, in exchange for fewer fake bookings.
