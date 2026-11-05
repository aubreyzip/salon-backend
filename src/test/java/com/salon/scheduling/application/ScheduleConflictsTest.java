package com.salon.scheduling.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.salon.salons.SalonMembership;
import com.salon.salons.StaffRole;
import com.salon.scheduling.BlockingAppointment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which conflicting appointments a caller may see in the 409 problem (decision 21): the same rules
 * as appointment reads (ADR-0010).
 */
class ScheduleConflictsTest {

	private static final UUID SALON = UUID.randomUUID();

	private static final UUID BRANCH_A = UUID.randomUUID();

	private static final UUID BRANCH_B = UUID.randomUUID();

	private static final UUID ME = UUID.randomUUID();

	private static final UUID COLLEAGUE = UUID.randomUUID();

	@Test
	void salonWideMembersSeeEveryAppointment() {
		SalonMembership manager = member(StaffRole.MANAGER, Set.of());

		assertThat(ScheduleConflicts.visibleTo(manager, appointment(BRANCH_B, COLLEAGUE))).isTrue();
	}

	@Test
	void receptionistsSeeAppointmentsOnlyInTheirBranches() {
		SalonMembership receptionist = member(StaffRole.RECEPTIONIST, Set.of(BRANCH_A));

		assertThat(ScheduleConflicts.visibleTo(receptionist, appointment(BRANCH_A, COLLEAGUE))).isTrue();
		assertThat(ScheduleConflicts.visibleTo(receptionist, appointment(BRANCH_B, COLLEAGUE))).isFalse();
	}

	@Test
	void staffSeeOnlyTheirOwnAppointments() {
		SalonMembership stylist = member(StaffRole.STAFF, Set.of(BRANCH_A));

		assertThat(ScheduleConflicts.visibleTo(stylist, appointment(BRANCH_A, ME))).isTrue();
		assertThat(ScheduleConflicts.visibleTo(stylist, appointment(BRANCH_A, COLLEAGUE))).isFalse();
	}

	private static SalonMembership member(StaffRole role, Set<UUID> branchIds) {
		return new SalonMembership(SALON, ME, UUID.randomUUID(), role, branchIds);
	}

	private static BlockingAppointment appointment(UUID branchId, UUID staffId) {
		return new BlockingAppointment(UUID.randomUUID(), branchId, staffId, Instant.parse("2026-11-16T08:00:00Z"),
				Instant.parse("2026-11-16T09:00:00Z"));
	}

}
