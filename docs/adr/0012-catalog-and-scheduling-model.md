# ADR-0012: Catalog and scheduling model

- Status: Accepted (Phase 3 decision register, confirmed 2026-11-04; decisions 21–26 added 2026-11-05), implemented (Phase 3: decisions 1–20 and the validation boundary of 21; Phase 4: the override of 21 and decisions 22–26)
- Date: 2026-11-04

## Context

Phase 3 adds what a salon sells (catalog) and when its staff can work (scheduling), plus the engine that turns both into bookable start times (report §7). Booking (Phase 4) builds on all three. The schema decides much of the behavior, and merged migrations cannot be edited, so the decisions below were settled before the code.

## Decision

Two new modules, with dependencies `salons ← catalog ← scheduling`:
- `catalog`: public API `ServiceCatalog` and `BookingRequirements`.
- `scheduling`: public extension point `BlockingAppointments` and `BlockingAppointment` (decision 21), which booking implements in Phase 4. This is an interface for another module to implement, an exception to "interfaces only for external systems": booking depends on scheduling, so scheduling cannot call booking directly without a module cycle.
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

### Appointments and schedule changes (decisions 21–26, added 2026-11-05)

These decisions mainly govern booking (Phase 4). Phase 3 implements only the validation boundary of decision 21; no appointment functionality exists yet.

**21. Existing appointments when schedules change.**
- **What is rejected:** normal schedule changes that conflict with future **blocking** appointments (`PENDING`, `CONFIRMED`) get `409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS`. This covers:
  - staff working hours;
  - branch opening hours;
  - time off;
  - closures;
  - creating, changing and removing special days.
- **What never blocks:** historical, cancelled, rejected, completed, no-show and expired appointments.
- **No silent invalidation:** existing appointments are never invalidated without someone deciding it.
- **Emergency override:** a separate, explicit path for authorized managers (permission: decision 25), with a required reason. It identifies the affected appointments, flags them for resolution, keeps their records, and makes them visible to authorized salon staff. The resolution workflow belongs to Phase 4.
- *Implemented in Phase 3* (the validation boundary):
  - The `scheduling` root package defines the extension point `BlockingAppointments`, returning `BlockingAppointment` records. Booking implements it in Phase 4. With no implementation (Phase 3), no change is blocked.
  - The check runs in `ScheduleConflicts` (application) and `ScheduleCoverage` (domain, pure).
  - **What counts as a conflict:** the change removes time the appointment needs. The appointment's `[start, blockEnd)`, buffer included, fits the hours before the change and not after. For time off, the new time off overlaps it. Appointments that did not fit before (e.g. booked with an override) are not blamed on an unrelated change. Closing exactly when the buffer ends is not a conflict.
  - **What the 409 contains:** `conflictCount` (all conflicts) and `appointments` (`id`, `branchId`, `staffId`, `start`, `end`). The list holds only the appointments the caller may view, under appointment-read rules: `APPOINTMENT_VIEW_ANY` in a covered branch, or `APPOINTMENT_VIEW_OWN` for their own.
  - **Not checked:** changes that only add time (deleting a closure or time off).
  - **Locking:** every checked change holds the salon row lock (time-off creation now takes it too).
- *Phase 4 design constraints:*
  - **Booking side of the extension point:** booking implements `BlockingAppointments` with only blocking statuses and `blockEnd > endingAfter`.
  - **No race with schedule changes:** booking must not commit an appointment while a conflicting schedule change is in flight. Recommended: booking takes the salon row lock `FOR SHARE`; schedule changes already take it `FOR UPDATE`, so bookings don't block each other but wait for schedule changes.
  - **Override shape:** an optional request member (e.g. `conflictOverride: {reason}`), an additive v1 change. Flagging uses an additional extension method or event, audited.

**22. Appointments in inactive branches.**
- Appointments stay associated with a branch after it is deactivated.
- Deactivation stops new **public** bookings (already true: inactive branches are not public). It does not delete, cancel or hide existing appointments.
- Read access follows ADR-0010 unchanged. Branch-scoped members keep read access to a branch's appointment history while their membership is active, their assignment to that branch exists, and they hold the read permission. Removing the assignment or deactivating the membership removes that access.
- A branch closing for good with future appointments goes through the conflict-resolution process of decision 21.

**23. No overlapping appointments for one customer, platform-wide.**
- A customer's active (blocking) appointments must not overlap, across all salons and branches.
- The interval is the appointment itself, `[start_at, end_at)`, **without** the staff cleanup buffer. Back-to-back appointments are allowed.
- Identity is the global customer account (`user_account` id), not `salon_client`. Unlinked walk-in records have no global identity and are not checked.
- *Phase 4 design constraints:* an application check (friendly error) plus a PostgreSQL exclusion constraint for concurrent requests: `EXCLUDE USING gist (customer_user_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&) WHERE (status IN ('PENDING','CONFIRMED') AND customer_user_id IS NOT NULL)`. It sits next to the staff constraint of ADR-0005, which uses `block_end_at`.

**24. At most five active future appointments per customer, platform-wide.**
- Counted: `PENDING`, `CONFIRMED`. Not counted: `COMPLETED`, `CANCELLED`, `REJECTED`, `NO_SHOW`, `EXPIRED`.
- Applies across all salons and branches.
- Staff bookings for an account-linked customer count. Unlinked walk-in records do not.
- *Phase 4 design constraints:* enforce it transactionally. A count alone races, so concurrent bookings by one customer must serialize. Recommended: a booking-owned per-customer row locked `FOR UPDATE` before counting, the same pattern as the salon row lock (ADR-0002). Advisory locks are the fallback.

**25. Permission for the emergency override.** (decided 2026-11-05)
- A new permission `APPOINTMENT_CONFLICT_OVERRIDE`, granted to OWNER and MANAGER only. Receptionists and staff don't get it.
- An override needs this permission **in addition** to the permission for the change itself. For example, a stylist can't override conflicts with their own time off.
- *Phase 4:* add the permission together with the override. Until something uses it, clients would see a permission that does nothing. Adding it to `SalonPermission` is additive in v1; clients must tolerate unknown values. In `StaffRole`, MANAGER gets it automatically as "all but `BILLING_MANAGE`"; the role-permission tests will confirm.

**26. Other changes that affect future appointments.** (decided 2026-11-05)
- These changes are handled like decision 21: the same `409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS`, with the same body and visibility rules, and the same emergency override (decision 25).

  | Change | Module | Conflicting appointments (future, blocking) |
  |---|---|---|
  | Staff deactivation | `salons` | All of the staff member's, any branch |
  | Branch unassignment | `salons` | The staff member's at the branches removed from their assignment |
  | `bookable` set to false | `salons` | All of the staff member's |
  | Skill removal (skills replaced without a service) | `catalog` | The staff member's that include that service |
  | Service archiving | `catalog` | Any that include the service |
  | Offering removal | `catalog` | Any at that branch that include the service |

- Not covered: branch deactivation. Under decision 22 it keeps existing appointments and only stops new public bookings. A branch closing for good goes through the conflict-resolution process.
- *Phase 4 design constraints:*
  - **Move the extension point down.** These checks live in `salons` and `catalog`, below `scheduling`, so they can't use `scheduling.BlockingAppointments`. Recommended: move the extension point into the `salons` root package, the lowest module that needs it, so booking implements a single interface. Add each appointment's service ids and queries by service.
  - **Same order of steps.** The checks run after the existing access checks and under the salon row lock. Membership changes already take it (ADR-0002). Catalog changes that run these checks must take it too.

## Consequences

- Booking (Phase 4) gets durations, buffer and prices from `ServiceCatalog.requirements(...)` and adds blocking appointments to the engine's absences. It still has to snapshot price and duration on the appointment.
- Single-branch salons must still create an offering per service and a skill per staff member. Clients should do this in one step (e.g. "offer at all branches", "all services" checkboxes).
- A weekly change of hours "from next Monday" takes one request: replace the branch's periods with the old period ending on Sunday and the new one starting on Monday.
- Working hours at a branch the staff member is no longer assigned to are kept. They produce no availability, but they still take part in the cross-branch overlap check until they are cleared.
- The cross-branch overlap rule lives in the application. Writes that bypass `WorkingHoursService` could break it, which is why only that service writes working hours.
- Times in responses are ISO local times (`"09:00:00"`). Requests accept `"09:00"`.
- The public availability endpoint is unauthenticated and does real work per call. It needs rate limiting before launch (Phase 8).
