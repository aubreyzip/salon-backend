package com.salon.salons;

import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import com.salon.shared.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch-scoped authorization (ADR-0010) with real memberships and assignments, through the
 * {@link TenantAccess} API that branch-specific features (schedules, appointments, clients) use.
 * <p>
 * Fixture: salon with branches A and B. Manager, receptionist and stylist are assigned to A only;
 * a second receptionist has no assignment.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BranchScopeIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	TenantAccess access;

	private UUID salonId;

	private UUID branchA;

	private UUID branchB;

	private TestUser owner;

	private TestUser manager;

	private TestUser receptionist;

	private TestUser unassignedReceptionist;

	private TestUser stylist;

	private UUID stylistStaffId;

	private UUID colleagueStaffId;

	@BeforeAll
	void createSalonWithTwoBranches() throws Exception {
		owner = api.signUp("Owner");
		manager = api.signUp("Manager");
		receptionist = api.signUp("Receptionist");
		unassignedReceptionist = api.signUp("Unassigned Receptionist");
		stylist = api.signUp("Stylist");
		salonId = api.createSalon(owner, "Branch Scope");
		branchA = api.createBranch(owner, salonId);
		branchB = api.createBranch(owner, salonId);
		assign(api.addMember(owner, salonId, "MANAGER", manager), "MANAGER", branchA);
		assign(api.addMember(owner, salonId, "RECEPTIONIST", receptionist), "RECEPTIONIST", branchA);
		api.addMember(owner, salonId, "RECEPTIONIST", unassignedReceptionist);
		stylistStaffId = api.addMember(owner, salonId, "STAFF", stylist);
		assign(stylistStaffId, "STAFF", branchA);
		colleagueStaffId = UUID.fromString(TestApi.read(api.post(owner.accessToken(), staffPath(), """
				{"displayName": "Colleague", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(branchA)), "$.id"));
	}

	@Test
	void ownersWorkInEveryBranch() {
		allowed(() -> access.requireInBranch(salonId, branchA, owner.id(), SalonPermission.APPOINTMENT_MANAGE_ANY));
		allowed(() -> access.requireInBranch(salonId, branchB, owner.id(), SalonPermission.APPOINTMENT_MANAGE_ANY));
	}

	@Test
	void managersKeepSalonWideAuthorityDespiteBranchAssignments() {
		allowed(() -> access.requireInBranch(salonId, branchB, manager.id(), SalonPermission.APPOINTMENT_MANAGE_ANY));
		allowed(() -> access.requireForStaffResource(salonId, branchB, colleagueStaffId, manager.id(),
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
	}

	@Test
	void receptionistsWorkOnlyInAssignedBranches() {
		allowed(() -> access.requireInBranch(salonId, branchA, receptionist.id(),
				SalonPermission.APPOINTMENT_MANAGE_ANY));
		denied("BRANCH_NOT_ASSIGNED", () -> access.requireInBranch(salonId, branchB, receptionist.id(),
				SalonPermission.APPOINTMENT_MANAGE_ANY));
		denied("BRANCH_NOT_ASSIGNED", () -> access.requireInBranch(salonId, branchA, unassignedReceptionist.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY));
	}

	@Test
	void branchAssignmentsNeverAddPermissions() {
		// Assigned to A, but receptionists cannot manage schedules or branches anywhere.
		denied("INSUFFICIENT_PERMISSION", () -> access.requireInBranch(salonId, branchA, receptionist.id(),
				SalonPermission.BRANCH_MANAGE));
		denied("INSUFFICIENT_PERMISSION", () -> access.requireForStaffResource(salonId, branchA, colleagueStaffId,
				receptionist.id(), SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
		// Stylists only have "own" permissions.
		denied("INSUFFICIENT_PERMISSION", () -> access.requireInBranch(salonId, branchA, stylist.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY));
	}

	@Test
	void stylistsManageOnlyTheirOwnResourcesInTheirBranches() {
		allowed(() -> access.requireForStaffResource(salonId, branchA, stylistStaffId, stylist.id(),
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
		denied("INSUFFICIENT_PERMISSION", () -> access.requireForStaffResource(salonId, branchA, colleagueStaffId,
				stylist.id(), SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
		denied("BRANCH_NOT_ASSIGNED", () -> access.requireForStaffResource(salonId, branchB, stylistStaffId,
				stylist.id(), SalonPermission.APPOINTMENT_VIEW_ANY, SalonPermission.APPOINTMENT_VIEW_OWN));
	}

	/** Staff resources without a branch (e.g. time off): scope means "works in one of my branches". */
	@Test
	void staffMemberResourcesRequireASharedBranchForBranchScopedMembers() throws Exception {
		UUID onlyInB = UUID.fromString(TestApi.read(api.post(owner.accessToken(), staffPath(), """
				{"displayName": "Only in B", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(branchB)), "$.id"));
		UUID unassigned = UUID.fromString(TestApi.read(api.post(owner.accessToken(), staffPath(), """
				{"displayName": "Unassigned", "role": "STAFF", "bookable": true}
				"""), "$.id"));

		allowed(() -> access.requireForStaffMember(salonId, colleagueStaffId, receptionist.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY, SalonPermission.APPOINTMENT_VIEW_OWN));
		denied("BRANCH_NOT_ASSIGNED", () -> access.requireForStaffMember(salonId, onlyInB, receptionist.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY, SalonPermission.APPOINTMENT_VIEW_OWN));
		// Salon-wide members reach everyone, also staff without branches.
		allowed(() -> access.requireForStaffMember(salonId, unassigned, manager.id(),
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
		allowed(() -> access.requireForStaffMember(salonId, stylistStaffId, stylist.id(),
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
		denied("INSUFFICIENT_PERMISSION", () -> access.requireForStaffMember(salonId, colleagueStaffId, stylist.id(),
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
		denied("STAFF_NOT_FOUND", () -> access.requireForStaffMember(salonId, UUID.randomUUID(), owner.id(),
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
	}

	@Test
	void assignmentChangesApplyImmediatelyWithoutNewTokens() throws Exception {
		TestUser movingReceptionist = api.signUp("Moving Receptionist");
		UUID staffId = api.addMember(owner, salonId, "RECEPTIONIST", movingReceptionist);
		assign(staffId, "RECEPTIONIST", branchA);
		allowed(() -> access.requireInBranch(salonId, branchA, movingReceptionist.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY));

		assign(staffId, "RECEPTIONIST", branchB);

		denied("BRANCH_NOT_ASSIGNED", () -> access.requireInBranch(salonId, branchA, movingReceptionist.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY));
		allowed(() -> access.requireInBranch(salonId, branchB, movingReceptionist.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY));
	}

	@Test
	void nonMembersAreRejectedBeforeAnyBranchCheck() throws Exception {
		TestUser outsider = api.signUp("Outsider");

		denied("SALON_NOT_FOUND", () -> access.requireInBranch(salonId, branchA, outsider.id(),
				SalonPermission.APPOINTMENT_VIEW_ANY));
		denied("SALON_NOT_FOUND", () -> access.requireForStaffResource(salonId, branchA, stylistStaffId,
				outsider.id(), SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
		denied("SALON_NOT_FOUND", () -> access.requireForStaffMember(salonId, UUID.randomUUID(), outsider.id(),
				SalonPermission.SCHEDULE_MANAGE_ANY, SalonPermission.SCHEDULE_MANAGE_OWN));
	}

	private void assign(UUID staffId, String role, UUID branchId) throws Exception {
		api.put(owner.accessToken(), staffPath() + "/" + staffId, """
				{"displayName": "Member", "role": "%s", "bookable": true, "branchIds": ["%s"]}
				""".formatted(role, branchId)).andExpect(status().isOk());
	}

	private String staffPath() {
		return "/api/v1/salons/" + salonId + "/staff";
	}

	private static void allowed(ThrowingCallable call) {
		assertThatCode(call).doesNotThrowAnyException();
	}

	private static void denied(String code, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
	}

}
