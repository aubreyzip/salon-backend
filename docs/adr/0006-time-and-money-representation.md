# ADR-0006: Time and money representation

- Status: Accepted
- Date: 2026-11-02

## Context

The first market is Turkey (fixed UTC+3 since 2016), but the product should not block international expansion. Scheduling mixes absolute moments (an appointment) with local patterns (a stylist works 09:00–18:00 on Mondays).

## Decision

**Time**
- Absolute moments: Java `Instant` (or `OffsetDateTime` at API edges), PostgreSQL `timestamptz`, API as ISO-8601 with offset.
- Weekly patterns: `DayOfWeek` + `LocalTime`, PostgreSQL `smallint` + `time`.
- Calendar days chosen by a user: `LocalDate`, interpreted in the **branch's** time zone.
- Every branch stores an IANA `time_zone` (e.g. `Europe/Istanbul`). All local-to-instant conversion goes through `ZoneId`. Never assume a fixed offset.
- JVM, containers and JDBC sessions run in UTC (`hibernate.jdbc.time_zone=UTC`). `LocalDateTime` is never used for moments.
- Code that needs "now" takes an injectable `Clock`.

**Money**
- `BigDecimal` in Java, `numeric(12,2)` + `currency char(3)` in PostgreSQL. Never floating point.

**Text**
- Normalize identifiers (e.g. emails) with `Locale.ROOT`, never the default locale: Turkish `I`/`ı`/`İ`/`i` casing breaks case-insensitive matching under `tr-TR`.

## Consequences

- DST gaps and overlaps for future markets are handled by `java.time` rules and need tests with a DST zone.
- Clients must send and display times with offsets and use the branch's zone for schedule views.
