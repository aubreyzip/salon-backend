package com.salon.salons;

import java.util.List;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reactivating deactivated staff members: same membership row, role, branches and account link;
 * same rank rules as deactivation; audited.
 */
@IntegrationTest
class StaffReactivationIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void reactivationRestoresAccessWithTheSameRoleAndBranches() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser receptionist = api.signUp("Receptionist");
		UUID salonId = api.createSalon(owner, "Returning Staff");
		UUID branchId = api.createBranch(owner, salonId);
		UUID staffId = api.addMember(owner, salonId, "RECEPTIONIST", receptionist);
		String member = "/api/v1/salons/" + salonId + "/staff/" + staffId;
		api.put(owner.accessToken(), member, """
				{"displayName": "Desk", "role": "RECEPTIONIST", "bookable": false, "branchIds": ["%s"]}
				""".formatted(branchId)).andExpect(status().isOk());
		api.post(owner.accessToken(), member + "/deactivate", null).andExpect(status().isOk());
		api.get(receptionist.accessToken(), "/api/v1/salons/" + salonId).andExpect(status().isNotFound());

		api.post(owner.accessToken(), member + "/reactivate", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.role").value("RECEPTIONIST"))
			.andExpect(jsonPath("$.linked").value(true))
			.andExpect(jsonPath("$.branchIds[0]").value(branchId.toString()));

		api.get(receptionist.accessToken(), "/api/v1/salons/" + salonId).andExpect(status().isOk());
		assertThat(auditActions(salonId, staffId)).containsSubsequence("salons.staff.deactivated",
				"salons.staff.reactivated");
	}

	@Test
	void reactivatingAnActiveMemberChangesNothing() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Already Active");
		UUID staffId = api.addMember(owner, salonId, "STAFF", stylist);

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId + "/reactivate", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true));

		assertThat(auditActions(salonId, staffId)).doesNotContain("salons.staff.reactivated");
	}

	@Test
	void reactivationFollowsTheRankRules() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser manager = api.signUp("Manager");
		TestUser otherManager = api.signUp("Other Manager");
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Rank Reactivation");
		api.addMember(owner, salonId, "MANAGER", manager);
		UUID otherManagerId = api.addMember(owner, salonId, "MANAGER", otherManager);
		UUID stylistId = api.addMember(owner, salonId, "STAFF", stylist);
		String staff = "/api/v1/salons/" + salonId + "/staff/";
		api.post(owner.accessToken(), staff + otherManagerId + "/deactivate", null).andExpect(status().isOk());
		api.post(owner.accessToken(), staff + stylistId + "/deactivate", null).andExpect(status().isOk());

		api.post(manager.accessToken(), staff + otherManagerId + "/reactivate", null)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ROLE_CHANGE_NOT_ALLOWED"));
		api.post(manager.accessToken(), staff + stylistId + "/reactivate", null).andExpect(status().isOk());
		api.post(stylist.accessToken(), staff + stylistId + "/reactivate", null)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_PERMISSION"));
	}

	@Test
	void formerMembersAreToldToAskForReactivationInsteadOfAcceptingNewInvitations() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Former Member");
		UUID staffId = api.addMember(owner, salonId, "STAFF", stylist);
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId + "/deactivate", null);

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Again", "role": "MANAGER", "bookable": true, "inviteEmail": "%s"}
				""".formatted(stylist.email())).andExpect(status().isCreated());
		api.post(stylist.accessToken(), "/api/v1/me/invitations/accept",
				"{\"token\": \"%s\"}".formatted(api.lastInvitationTokenSentTo(stylist.email())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FORMER_MEMBER"));

		// A new invitation must not become a way to come back with a different (higher) role.
		api.get(stylist.accessToken(), "/api/v1/salons/" + salonId).andExpect(status().isNotFound());
	}

	@Test
	void reactivatedProfilesDoNotRestoreRevokedInvitations() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "No Token Revival");
		String staffId = read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Invitee", "role": "STAFF", "bookable": true, "inviteEmail": "%s"}
				""".formatted(invitee.email())), "$.id");
		String revokedToken = api.lastInvitationTokenSentTo(invitee.email());
		String member = "/api/v1/salons/" + salonId + "/staff/" + staffId;
		api.post(owner.accessToken(), member + "/deactivate", null).andExpect(status().isOk());

		api.post(owner.accessToken(), member + "/reactivate", null)
			.andExpect(jsonPath("$.linked").value(false))
			.andExpect(jsonPath("$.invitationPending").value(false));
		api.post(invitee.accessToken(), "/api/v1/me/invitations/accept",
				"{\"token\": \"%s\"}".formatted(revokedToken))
			.andExpect(status().isBadRequest());

		api.post(owner.accessToken(), member + "/invitations", "{\"email\": \"%s\"}".formatted(invitee.email()))
			.andExpect(status().isAccepted());
		api.post(invitee.accessToken(), "/api/v1/me/invitations/accept",
				"{\"token\": \"%s\"}".formatted(api.lastInvitationTokenSentTo(invitee.email())))
			.andExpect(status().isOk());
	}

	@Test
	void suspendedSalonsCannotReactivateStaff() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Suspended Reactivation");
		api.createBranch(owner, salonId);
		UUID staffId = api.addMember(owner, salonId, "STAFF", stylist);
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId + "/deactivate", null);
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/submit-for-review", null);
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/approve", null);
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/suspend", "{\"note\": \"Check\"}")
			.andExpect(status().isOk());

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId + "/reactivate", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
	}

	private List<String> auditActions(UUID salonId, UUID staffId) {
		return jdbc.queryForList(
				"SELECT action FROM audit_log WHERE salon_id = ? AND entity_id = ? ORDER BY occurred_at, id",
				String.class, salonId, staffId.toString());
	}

}
