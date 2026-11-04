# ADR-0012: Catalog and scheduling model

- Status: Accepted (Phase 3 decision register, confirmed 2026-11-04), implemented (Phase 3)
- Date: 2026-11-04

## Context

Phase 3 adds what a salon sells (catalog) and when its staff can work (scheduling), plus the engine that turns both into bookable start times (report §7). Booking (Phase 4) builds on all three. The schema decides much of the behavior, and merged migrations cannot be edited, so the decisions below were settled before the code.

## Decision

Two new modules, with dependencies `salons ← catalog ← scheduling`:
- `catalog`: public API `ServiceCatalog` and `BookingRequirements`.
- `scheduling`: no public API yet; booking will need one in Phase 4.
- Both read salon, branch and staff facts through the new `salons` root types `SalonDirectory`, `BranchInfo` and `StaffInfo`.

### Catalog

| # | Topic | Decision |
|---|---|---|
| 2 | Several services in one booking | One staff member performs all of them, back to back. Durations add up. |
| 5 | Buffer time | Default per service, overridable per branch. Applied **once**, after the whole booking: the **largest** buffer among the booked services. Duration plus buffer must fit into working hours. |
| 6 | Overrides | Branch: price, duration, buffer. Staff skill: duration only, salon-wide (not per branch). Precedence for duration: staff → branch → service default. |
| 7 | Price | Fixed price plus an optional "starting from" flag (`priceFrom`). `numeric(12,2)` + `char(3)` currency. A CHECK constraint allows only `TRY` (relax it per market). |
| 8 | Deleting services | Never. Services are **archived** and can be restored. Archived services are not bookable and cannot get new offerings; offerings and skills are kept. |
| 9 | Online bookable | Boolean per service, default true. False = only the salon books it (enforced in Phase 4). |
| 10 | Categories | Platform taxonomy `service_category` (seeded reference data, stable ids, edited by migration). Every service maps to exactly one category. Salons can also define their own optional display **sections**. Deleting a section keeps its services without a section. |
| 18 | Staff skills | Explicit. A staff member is offered for a service only with a skill row. |
| — | Offerings | Explicit. A service is bookable at a branch only through an offering row (`service_offering`). |

### Scheduling

| # | Topic | Decision |
|---|---|---|
| 1 | Inactive branches | Can be fully configured (offerings, hours, schedules). They have no public catalog or availability. Branch assignments still restrict operational access (ADR-0010). |
| 4 | Recurrence | Weekly templates plus exceptions only. No RRULE. |
| 11 | Intervals | Several intervals per day (lunch breaks). No overnight intervals: `start < end` within one day. Whole minutes. |
| 12 | Staff hours outside opening hours | Saved as entered. The engine intersects them with opening hours. |
| 13 | Exceptions | **Special days**: one date with its own hours, or closed (no intervals). **Closures**: inclusive date ranges (renovation, holidays). A closure wins over a special day. |
| 14 | Time off | Instants, full or partial days, all branches. Effective immediately, audited, no approval workflow. Only `SCHEDULE_MANAGE_ANY`, or the staff member themselves (`*_OWN`), can read it, because the reason may be personal. |
| 19 | Staff at several branches | Working hours are date-bounded weekly **periods** per staff member and branch (`effective_from` to `effective_to`, inclusive; open-ended allowed). Periods at one branch must not overlap in dates (`400 OVERLAPPING_PERIODS`). Hours at different branches must not overlap in time on any shared date (`409 WORKING_HOURS_CONFLICT`). Both are checked under the salon row lock (ADR-0002); there is no database constraint for this. Hours can only be added for active staff assigned to the branch; clearing them is always allowed. |

### Availability

| # | Topic | Decision |
|---|---|---|
| 3 | "Any available staff" | Supported from the start. Each slot lists every free staff member. Booking picks one in Phase 4. |
| 15 | Access | Public (`/api/v1/public/branches/{id}/availability`) only for active branches of `ACTIVE` salons. Others are `404 BRANCH_NOT_FOUND`. Only services offered at the branch and bookable online. Only staff who are active, bookable, assigned to the branch and skilled in every service. The branch's minimum notice applies. |
| 16 | Slot grid | Clock-aligned: starts at multiples of the branch's slot interval from midnight (e.g. :00/:15/:30/:45), not from the start of work. |
| 17 | Range | At most 31 days per query, 1–5 services. Every day of the range is returned (days without slots have an empty list), so one call fills a month view. Days beyond the branch's `max_advance_days` are empty. |
| 20 | Salon-side availability | Phase 4 (walk-ins, phone bookings: every service, no minimum notice). The engine takes notice and limits as parameters already. |

The engine (`AvailabilityEngine`) is a pure function over loaded data, as report §7 describes:
1. Opening hours for the day: closure → none, else special day, else weekly.
2. Intersect them with the staff member's working hours (the period effective on that date).
3. Convert to instants in the branch's IANA zone.
4. Subtract absences: time off, and blocking appointments from Phase 4.
5. Offer a grid start if duration + buffer fits into one free range.

DST: local times that do not exist (spring forward) are skipped. Local times that occur twice (fall back) are offered once, at the earlier instant.

### Authorization (ADR-0010)

| Area | Change | Read |
|---|---|---|
| Catalog: services, sections, offerings, staff skills | `require(CATALOG_MANAGE)` | any member |
| Branch calendar: opening hours, special days, closures | `require(BRANCH_MANAGE)` | any member |
| Working hours | `requireForStaffResource(SCHEDULE_MANAGE_ANY, SCHEDULE_MANAGE_OWN)` in the target branch | any member; branch-scoped members see only their branches, except their own hours |
| Time off | new `TenantAccess.requireForStaffMember(SCHEDULE_MANAGE_ANY, SCHEDULE_MANAGE_OWN)`: staff resources without a branch; branch-scoped members acting on someone else need a shared branch | same check as change |

Every change also calls `SalonDirectory.ensureModifiable` (suspended salons get `409 SALON_SUSPENDED`). Writes that create per-branch rows or check cross-branch invariants (branch calendar, working hours) lock the salon row first.

## Consequences

- Booking (Phase 4) gets durations, buffer and prices from `ServiceCatalog.requirements(...)` and adds blocking appointments to the engine's absences. It still has to snapshot price and duration on the appointment.
- Single-branch salons must still create an offering per service and a skill per staff member. Clients should do this in one step (e.g. "offer at all branches", "all services" checkboxes).
- A weekly change of hours "from next Monday" takes one request: replace the branch's periods with the old period ending on Sunday and the new one starting on Monday.
- The cross-branch overlap rule lives in the application. Writes that bypass `WorkingHoursService` could break it, which is why only that service writes working hours.
- Times in responses are ISO local times (`"09:00:00"`). Requests accept `"09:00"`.
- The public availability endpoint is unauthenticated and does real work per call. It needs rate limiting before launch (Phase 8).
