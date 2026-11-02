package com.salon.salons;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SalonMembershipTest {

	private static final UUID ASSIGNED = UUID.randomUUID();

	private static final UUID OTHER = UUID.randomUUID();

	private static SalonMembership member(StaffRole role, Set<UUID> branches) {
		return new SalonMembership(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), role, branches);
	}

	@Test
	void ownersAndManagersCoverEveryBranchWhateverTheirAssignments() {
		for (StaffRole role : new StaffRole[] { StaffRole.OWNER, StaffRole.MANAGER }) {
			SalonMembership membership = member(role, Set.of(ASSIGNED));

			assertThat(membership.coversBranch(OTHER)).as(role.name()).isTrue();
			assertThat(membership.branchRestriction()).as(role.name()).isEmpty();
		}
	}

	@Test
	void receptionistsAndStaffCoverOnlyTheirAssignedBranches() {
		for (StaffRole role : new StaffRole[] { StaffRole.RECEPTIONIST, StaffRole.STAFF }) {
			SalonMembership membership = member(role, Set.of(ASSIGNED));

			assertThat(membership.coversBranch(ASSIGNED)).as(role.name()).isTrue();
			assertThat(membership.coversBranch(OTHER)).as(role.name()).isFalse();
			assertThat(membership.branchRestriction()).as(role.name()).contains(Set.of(ASSIGNED));
		}
	}

	@Test
	void withoutAssignmentsBranchScopedRolesCoverNoBranch() {
		SalonMembership membership = member(StaffRole.RECEPTIONIST, Set.of());

		assertThat(membership.coversBranch(ASSIGNED)).isFalse();
		assertThat(membership.branchRestriction()).contains(Set.of());
	}

	@Test
	void assignmentsAreCopiedSoTheyCannotChangeAfterResolution() {
		Set<UUID> branches = new HashSet<>(Set.of(ASSIGNED));
		SalonMembership membership = member(StaffRole.STAFF, branches);

		branches.add(OTHER);

		assertThat(membership.coversBranch(OTHER)).isFalse();
	}

}
