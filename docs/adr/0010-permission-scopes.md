# ADR-0010: Permission scopes — salon-wide, assigned branches, own resources

- Status: Accepted
- Date: 2026-11-03

## Context

Permissions must respect both salon membership and branch assignments. Receptionists and stylists should only do operational work (appointment books, schedules, client handling) in the branches they work at. Branch assignments must not give anyone management authority. The model must carry employee schedules (Phase 3), appointments (Phase 4) and customer privacy without a redesign.

Before this ADR, permissions were salon-wide and branch assignments (`staff_branch`) were only recorded.

## Decision

Authorization has three independent layers. A request must pass all that apply.

| Layer | Question | Source |
|---|---|---|
| Permission | *What* may the member do? | `StaffRole` → `SalonPermission` set (unchanged) |
| Branch scope | *Where* may they do branch-specific work? | `StaffRole.branchScope()` + the member's branch assignments |
| Resource check | *Whose* resource? | `*_OWN` permissions require the resource's staff member to be the caller; `*_ANY` do not |

Branch scope per role:

| Role | Scope | Meaning |
|---|---|---|
| OWNER | `SALON` | All branches |
| MANAGER | `SALON` | All branches. Management authority comes from permissions and rank, not assignments |
| RECEPTIONIST | `ASSIGNED_BRANCHES` | Only assigned branches; none if unassigned |
| STAFF | `ASSIGNED_BRANCHES` | Only assigned branches, and only own resources (`*_OWN`) |

`TenantAccess` is the only place these checks live:

- `require(salon, user, permission)`: salon-level work (settings, branch setup, staff management, catalog, billing). Permission only.
- `requireInBranch(salon, branch, user, permission)`: branch-specific operational work. Permission + scope.
- `requireForStaffResource(salon, branch, resourceStaff, user, anyPermission, ownPermission)`: one staff member's resource in a branch (their schedule, their appointments). (`any`, or `own` for the caller's own resource) + scope.
- `requireForStaffMember(salon, resourceStaff, user, anyPermission, ownPermission)` (Phase 3): one staff member's resource that is not tied to a branch (their time off). (`any`, or `own` for the caller's own resource). A branch-scoped member acting on someone else must share at least one branch with that staff member. Unknown staff ids give `404 STAFF_NOT_FOUND`, after the membership check.
- `SalonMembership.branchRestriction()`: for list queries; empty for salon-wide members, otherwise the branch ids results must be filtered to.

Errors: `404 SALON_NOT_FOUND` (not a member), `403 INSUFFICIENT_PERMISSION`, `403 BRANCH_NOT_ASSIGNED`.

What is salon-level (no branch scope):
- Salon settings, branch setup (`BRANCH_MANAGE`), staff management (`STAFF_MANAGE` + rank rules), catalog, billing.
- Reading salon and branch details and the staff directory: open to all active members. This is not operational data, and branch details become public once a salon is listed.

## Consequences

- **No existing behavior changed.** No Phase 2 endpoint is branch-operational, so every endpoint keeps its behavior; the tenant-isolation matrix is unchanged. The checks are ready for Phase 3/4 features and are tested against real memberships (`BranchScopeIntegrationTests`).
- **Assignments only restrict.** They never add permissions (`branchAssignmentsNeverAddPermissions`).
- **Changes apply immediately.** Membership and assignments are resolved per request, not stored in tokens, so a re-assignment takes effect on the next request.
- **Unassigned receptionists and stylists can do no branch work**, so onboarding must assign branches. Clients should show that.
- **Scope is per role.** If a role later needs mixed scope (e.g. salon-wide client search for receptionists), `StaffRole` can map scope per permission without changing the `TenantAccess` API.
- **Branch-scoped managers later.** If managers should be limited to their branches, it is a one-line scope change plus tests. That would change existing behavior, so it needs an explicit decision.

## Open decisions for later phases

- **Phase 4, client privacy:** salon client records are tenant-level. Recommended: branch-scoped roles see only clients with appointments in their branches (via `branchRestriction()`); owners and managers see all.
- **Inactive branches (decided in Phase 3, ADR-0012 decisions 1 and 22):** they can be fully configured (offerings, hours, schedules) and are not public. Their appointments stay; deactivation does not cancel them or change read access. Branch-scoped members keep reading a branch's appointment history only while their membership is active, their assignment to that branch exists, and they hold the read permission. These are the normal rules of this ADR; inactive branches get no exception.

## Phase 3 usage (ADR-0012)

- Catalog changes use `require(CATALOG_MANAGE)`, branch calendar changes `require(BRANCH_MANAGE)`. Reads are member-wide.
- Working hours use `requireForStaffResource(SCHEDULE_MANAGE_ANY, SCHEDULE_MANAGE_OWN)` for the target branch. Lists are filtered with `branchRestriction()`, except for the caller's own hours.
- Time off uses `requireForStaffMember(SCHEDULE_MANAGE_ANY, SCHEDULE_MANAGE_OWN)` for reading and changing.
