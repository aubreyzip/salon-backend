package com.salon.salons;

import java.util.Arrays;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.salon.TestApi.branchJson;
import static com.salon.TestApi.read;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch activation and staff-to-branch assignments.
 * <p>
 * Assignments limit where receptionists and staff do branch-specific work (ADR-0010, tested in
 * {@link BranchScopeIntegrationTests}). They never add permissions. Salon-level reads (branch
 * details, staff directory) stay open to all members: they are not operational and branch details
 * become public once the salon is listed.
 */
@IntegrationTest
class BranchLifecycleIntegrationTests {

	@Autowired
	TestApi api;

	@Test
	void branchCanBeDeactivatedAndReactivated() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonId = api.createSalon(owner, "Branch Lifecycle");
		String branch = "/api/v1/salons/" + salonId + "/branches/" + api.createBranch(owner, salonId);

		api.post(owner.accessToken(), branch + "/deactivate", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));
		api.get(owner.accessToken(), branch).andExpect(jsonPath("$.active").value(false));
		// Idempotent.
		api.post(owner.accessToken(), branch + "/deactivate", null).andExpect(jsonPath("$.active").value(false));

		api.post(owner.accessToken(), branch + "/activate", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void inactiveBranchesDoNotCountTowardsReviewReadiness() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonId = api.createSalon(owner, "Inactive Only");
		String branch = "/api/v1/salons/" + salonId + "/branches/" + api.createBranch(owner, salonId);
		String submit = "/api/v1/salons/" + salonId + "/submit-for-review";

		api.post(owner.accessToken(), branch + "/deactivate", null).andExpect(status().isOk());
		api.post(owner.accessToken(), submit, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_NOT_READY_FOR_REVIEW"));

		api.post(owner.accessToken(), branch + "/activate", null).andExpect(status().isOk());
		api.post(owner.accessToken(), submit, null).andExpect(status().isOk());
	}

	@Test
	void branchesOfASuspendedSalonCannotBeActivatedOrDeactivated() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		UUID salonId = api.createSalon(owner, "Suspended Branches");
		String branch = "/api/v1/salons/" + salonId + "/branches/" + api.createBranch(owner, salonId);
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/submit-for-review", null);
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/approve", null);
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/suspend", "{\"note\": \"Check\"}")
			.andExpect(status().isOk());

		api.post(owner.accessToken(), branch + "/deactivate", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
		api.put(owner.accessToken(), branch, branchJson("Renamed", true)).andExpect(status().isConflict());
		api.get(owner.accessToken(), branch).andExpect(status().isOk());
	}

	@Test
	void branchAssignmentsCanBeAddedAndRemoved() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser otherOwner = api.signUp("Other Owner");
		UUID salonId = api.createSalon(owner, "Assignments");
		UUID first = api.createBranch(owner, salonId);
		UUID second = api.createBranch(owner, salonId);
		UUID foreign = api.createBranch(otherOwner, api.createSalon(otherOwner, "Foreign Branches"));
		String staff = "/api/v1/salons/" + salonId + "/staff";
		String staffId = read(api.post(owner.accessToken(), staff, """
				{"displayName": "Stylist", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(first)), "$.id");

		api.put(owner.accessToken(), staff + "/" + staffId, assignment(first, second))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.branchIds", containsInAnyOrder(first.toString(), second.toString())));
		api.put(owner.accessToken(), staff + "/" + staffId, assignment(second))
			.andExpect(jsonPath("$.branchIds", containsInAnyOrder(second.toString())));
		api.put(owner.accessToken(), staff + "/" + staffId, assignment())
			.andExpect(jsonPath("$.branchIds", empty()));
		api.put(owner.accessToken(), staff + "/" + staffId, assignment(second, foreign))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_IN_SALON"));

		api.get(owner.accessToken(), staff)
			.andExpect(jsonPath("$[?(@.id == '%s')].branchIds[*]".formatted(staffId), empty()));
	}

	@Test
	void branchAssignmentsDoNotGrantManagementOrRestrictSalonLevelReads() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser stylist = api.signUp("Stylist");
		TestUser manager = api.signUp("Manager");
		UUID salonId = api.createSalon(owner, "Branch Scoping");
		UUID assigned = api.createBranch(owner, salonId);
		UUID other = api.createBranch(owner, salonId);
		String branches = "/api/v1/salons/" + salonId + "/branches/";
		UUID stylistStaffId = api.addMember(owner, salonId, "STAFF", stylist);
		UUID managerStaffId = api.addMember(owner, salonId, "MANAGER", manager);
		api.put(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + stylistStaffId,
				"{\"displayName\": \"Stylist\", \"role\": \"STAFF\", \"bookable\": true, \"branchIds\": [\"%s\"]}"
					.formatted(assigned))
			.andExpect(status().isOk());
		api.put(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + managerStaffId,
				"{\"displayName\": \"Manager\", \"role\": \"MANAGER\", \"bookable\": false, \"branchIds\": [\"%s\"]}"
					.formatted(assigned))
			.andExpect(status().isOk());

		// Stylist: can read every branch of the salon (branch details become public once listed) ...
		api.get(stylist.accessToken(), branches + other).andExpect(status().isOk());
		// ... but cannot change any branch, including their own.
		api.put(stylist.accessToken(), branches + assigned, branchJson("Mine", true))
			.andExpect(status().isForbidden());
		api.post(stylist.accessToken(), branches + other + "/deactivate", null).andExpect(status().isForbidden());

		// Manager assigned to one branch still manages all branches (salon-wide permission).
		api.put(manager.accessToken(), branches + other, branchJson("Managed", true)).andExpect(status().isOk());
	}

	private static String assignment(UUID... branchIds) {
		String ids = String.join(", ", Arrays.stream(branchIds).map(id -> "\"" + id + "\"").toList());
		return "{\"displayName\": \"Stylist\", \"role\": \"STAFF\", \"bookable\": true, \"branchIds\": [%s]}"
			.formatted(ids);
	}

}
