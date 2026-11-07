package com.salon.salons;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which conflicting appointments a caller may see in the 409 problem (decision 21): the same rules
 * as appointment reads (ADR-0010).
 */
class AppointmentConflictsTest {

	private static final UUID SALON = UUID.randomUUID();

	private static final UUID BRANCH_A = UUID.randomUUID();

	private static final UUID BRANCH_B = UUID.randomUUID();

	private static final UUID ME = UUID.randomUUID();

	private static final UUID COLLEAGUE = UUID.randomUUID();

	@Test
	void salonWideMembersSeeEveryAppointment() {
		SalonMembership manager = member(StaffRole.MANAGER, Set.of());

		assertThat(AppointmentConflicts.visibleTo(manager, appointment(BRANCH_B, COLLEAGUE))).isTrue();
	}

	@Test
	void receptionistsSeeAppointmentsOnlyInTheirBranches() {
		SalonMembership receptionist = member(StaffRole.RECEPTIONIST, Set.of(BRANCH_A));

		assertThat(AppointmentConflicts.visibleTo(receptionist, appointment(BRANCH_A, COLLEAGUE))).isTrue();
		assertThat(AppointmentConflicts.visibleTo(receptionist, appointment(BRANCH_B, COLLEAGUE))).isFalse();
	}

	@Test
	void staffSeeOnlyTheirOwnAppointments() {
		SalonMembership stylist = member(StaffRole.STAFF, Set.of(BRANCH_A));

		assertThat(AppointmentConflicts.visibleTo(stylist, appointment(BRANCH_A, ME))).isTrue();
		assertThat(AppointmentConflicts.visibleTo(stylist, appointment(BRANCH_A, COLLEAGUE))).isFalse();
	}

	private static SalonMembership member(StaffRole role, Set<UUID> branchIds) {
		return new SalonMembership(SALON, ME, UUID.randomUUID(), role, branchIds);
	}

	private static BlockingAppointment appointment(UUID branchId, UUID staffId) {
		return new BlockingAppointment(UUID.randomUUID(), branchId, staffId, Set.of(), Instant.parse("2026-11-16T08:00:00Z"),
				Instant.parse("2026-11-16T09:00:00Z"));
	}

}
