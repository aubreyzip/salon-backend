package com.salon.salons;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaffRoleTest {

	@Test
	void ownersHaveEveryPermission() {
		assertThat(StaffRole.OWNER.permissions()).containsExactlyInAnyOrder(SalonPermission.values());
	}

	@Test
	void managersHaveEverythingExceptBillingManagementIncludingTheConflictOverride() {
		assertThat(StaffRole.MANAGER.permissions()).containsExactlyInAnyOrder(SalonPermission.SALON_MANAGE,
				SalonPermission.BRANCH_MANAGE, SalonPermission.STAFF_MANAGE, SalonPermission.CATALOG_MANAGE,
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN,
				SalonPermission.APPOINTMENT_VIEW_ANY, SalonPermission.APPOINTMENT_VIEW_OWN,
				SalonPermission.APPOINTMENT_MANAGE_ANY, SalonPermission.APPOINTMENT_MANAGE_OWN,
				SalonPermission.CLIENT_VIEW, SalonPermission.BILLING_VIEW,
				SalonPermission.APPOINTMENT_CONFLICT_OVERRIDE);
	}

	@Test
	void stylistsOnlyManageTheirOwnWork() {
		assertThat(StaffRole.STAFF.permissions()).containsExactlyInAnyOrder(SalonPermission.SCHEDULE_MANAGE_OWN,
				SalonPermission.APPOINTMENT_VIEW_OWN, SalonPermission.APPOINTMENT_MANAGE_OWN);
	}

	@Test
	void receptionistsHandleAppointmentsAndClientsButNotSetup() {
		assertThat(StaffRole.RECEPTIONIST.permissions()).containsExactlyInAnyOrder(
				SalonPermission.APPOINTMENT_VIEW_ANY, SalonPermission.APPOINTMENT_MANAGE_ANY,
				SalonPermission.CLIENT_VIEW);
	}

	@Test
	void permissionSetsCannotBeModifiedAtRuntime() {
		assertThatThrownBy(() -> StaffRole.STAFF.permissions().add(SalonPermission.STAFF_MANAGE))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ownersAndManagersWorkSalonWideOthersOnlyInAssignedBranches() {
		assertThat(StaffRole.OWNER.branchScope()).isEqualTo(PermissionScope.SALON);
		assertThat(StaffRole.MANAGER.branchScope()).isEqualTo(PermissionScope.SALON);
		assertThat(StaffRole.RECEPTIONIST.branchScope()).isEqualTo(PermissionScope.ASSIGNED_BRANCHES);
		assertThat(StaffRole.STAFF.branchScope()).isEqualTo(PermissionScope.ASSIGNED_BRANCHES);
	}

	@Test
	void ranksAreStrictlyOrdered() {
		assertThat(StaffRole.OWNER.outranks(StaffRole.MANAGER)).isTrue();
		assertThat(StaffRole.MANAGER.outranks(StaffRole.RECEPTIONIST)).isTrue();
		assertThat(StaffRole.RECEPTIONIST.outranks(StaffRole.STAFF)).isTrue();
		assertThat(StaffRole.MANAGER.outranks(StaffRole.MANAGER)).isFalse();
		assertThat(StaffRole.STAFF.outranks(StaffRole.OWNER)).isFalse();
	}

}
