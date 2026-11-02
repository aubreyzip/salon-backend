package com.salon.salons;

import java.util.Locale;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.salon.TestApi.read;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class StaffIntegrationTests {

	@Autowired
	TestApi api;

	@Test
	void invitedPersonLinksTheirAccountAndGetsTheRole() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Invite Salon");
		UUID branchId = api.createBranch(owner, salonId);
		String staff = "/api/v1/salons/" + salonId + "/staff";

		String staffId = read(api.post(owner.accessToken(), staff, """
				{"displayName": "Mehmet Usta", "title": "Senior Barber", "role": "STAFF", "bookable": true,
				 "branchIds": ["%s"], "inviteEmail": "%s"}
				""".formatted(branchId, stylist.email().toUpperCase(Locale.ROOT)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.linked").value(false))
			.andExpect(jsonPath("$.invitationPending").value(true))
			.andExpect(jsonPath("$.branchIds[0]").value(branchId.toString())), "$.id");

		api.post(stylist.accessToken(), "/api/v1/me/invitations/accept",
				"{\"token\": \"%s\"}".formatted(api.lastInvitationTokenSentTo(stylist.email())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("STAFF"))
			.andExpect(jsonPath("$.staffId").value(staffId));

		api.get(stylist.accessToken(), "/api/v1/me/salons")
			.andExpect(jsonPath("$[*].salonId", hasItem(salonId.toString())));
		api.get(owner.accessToken(), staff)
			.andExpect(jsonPath("$[?(@.id == '%s')].linked".formatted(staffId)).value(hasItem(true)))
			.andExpect(jsonPath("$[?(@.id == '%s')].invitationPending".formatted(staffId)).value(hasItem(false)));
	}

	@Test
	void invitationsAreBoundToTheInvitedEmailAndSingleUse() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invited = api.signUp("Invited");
		TestUser someoneElse = api.signUp("Someone Else");
		UUID salonId = api.createSalon(owner, "Bound Invites");
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Invited", "role": "STAFF", "bookable": true, "inviteEmail": "%s"}
				""".formatted(invited.email())).andExpect(status().isCreated());
		String token = "{\"token\": \"%s\"}".formatted(api.lastInvitationTokenSentTo(invited.email()));

		api.post(someoneElse.accessToken(), "/api/v1/me/invitations/accept", token)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INVITATION_EMAIL_MISMATCH"));
		api.post(invited.accessToken(), "/api/v1/me/invitations/accept", token).andExpect(status().isOk());
		api.post(invited.accessToken(), "/api/v1/me/invitations/accept", token)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
	}

	@Test
	void resendingAnInvitationInvalidatesThePreviousOne() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invited = api.signUp("Invited");
		UUID salonId = api.createSalon(owner, "Resend");
		String staffId = read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Invited", "role": "STAFF", "bookable": true, "inviteEmail": "%s"}
				""".formatted(invited.email())), "$.id");
		String firstToken = api.lastInvitationTokenSentTo(invited.email());

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId + "/invitations",
				"{\"email\": \"%s\"}".formatted(invited.email()))
			.andExpect(status().isAccepted());

		api.post(invited.accessToken(), "/api/v1/me/invitations/accept", "{\"token\": \"%s\"}".formatted(firstToken))
			.andExpect(status().isBadRequest());
		api.post(invited.accessToken(), "/api/v1/me/invitations/accept",
				"{\"token\": \"%s\"}".formatted(api.lastInvitationTokenSentTo(invited.email())))
			.andExpect(status().isOk());
	}

	@Test
	void managersCanOnlyManageLowerRoles() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser manager = api.signUp("Manager");
		UUID salonId = api.createSalon(owner, "Rank Rules");
		UUID managerStaffId = api.addMember(owner, salonId, "MANAGER", manager);
		String staff = "/api/v1/salons/" + salonId + "/staff";

		api.post(manager.accessToken(), staff, "{\"displayName\": \"X\", \"role\": \"MANAGER\", \"bookable\": true}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ROLE_CHANGE_NOT_ALLOWED"));
		api.post(manager.accessToken(), staff, "{\"displayName\": \"X\", \"role\": \"OWNER\", \"bookable\": true}")
			.andExpect(status().isForbidden());
		String receptionistId = read(api.post(manager.accessToken(), staff,
				"{\"displayName\": \"Desk\", \"role\": \"RECEPTIONIST\", \"bookable\": false}")
			.andExpect(status().isCreated()), "$.id");

		// Own profile yes, own role no.
		api.put(manager.accessToken(), staff + "/" + managerStaffId,
				"{\"displayName\": \"Manager M.\", \"role\": \"MANAGER\", \"bookable\": true}")
			.andExpect(status().isOk());
		api.put(manager.accessToken(), staff + "/" + managerStaffId,
				"{\"displayName\": \"Manager M.\", \"role\": \"OWNER\", \"bookable\": true}")
			.andExpect(status().isForbidden());
		// Cannot promote a lower member to their own rank.
		api.put(manager.accessToken(), staff + "/" + receptionistId,
				"{\"displayName\": \"Desk\", \"role\": \"MANAGER\", \"bookable\": false}")
			.andExpect(status().isForbidden());

		String ownerStaffId = read(api.get(owner.accessToken(), staff), "$[?(@.role == 'OWNER')].id")
			.replaceAll("[\\[\\]\"]", "");
		api.post(manager.accessToken(), staff + "/" + ownerStaffId + "/deactivate", null)
			.andExpect(status().isForbidden());
	}

	@Test
	void managersCannotManageOtherManagersOrUnlinkedOwnerProfiles() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser manager = api.signUp("Manager");
		TestUser peer = api.signUp("Peer Manager");
		UUID salonId = api.createSalon(owner, "Peer Managers");
		api.addMember(owner, salonId, "MANAGER", manager);
		UUID peerStaffId = api.addMember(owner, salonId, "MANAGER", peer);
		String staff = "/api/v1/salons/" + salonId + "/staff";
		String unlinkedOwnerId = read(api.post(owner.accessToken(), staff,
				"{\"displayName\": \"Future Co-owner\", \"role\": \"OWNER\", \"bookable\": false}"), "$.id");

		api.put(manager.accessToken(), staff + "/" + peerStaffId,
				"{\"displayName\": \"Peer\", \"role\": \"STAFF\", \"bookable\": true}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ROLE_CHANGE_NOT_ALLOWED"));
		api.post(manager.accessToken(), staff + "/" + peerStaffId + "/deactivate", null)
			.andExpect(status().isForbidden());
		// Inviting someone onto an owner profile would hand out ownership.
		api.post(manager.accessToken(), staff + "/" + unlinkedOwnerId + "/invitations",
				"{\"email\": \"%s\"}".formatted(manager.email()))
			.andExpect(status().isForbidden());
		api.put(manager.accessToken(), staff + "/" + unlinkedOwnerId,
				"{\"displayName\": \"X\", \"role\": \"STAFF\", \"bookable\": true}")
			.andExpect(status().isForbidden());
	}

	@Test
	void membersWithoutStaffManagementCannotChangeTheirOwnMembership() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser stylist = api.signUp("Stylist");
		TestUser receptionist = api.signUp("Receptionist");
		UUID salonId = api.createSalon(owner, "Self Escalation");
		UUID stylistStaffId = api.addMember(owner, salonId, "STAFF", stylist);
		UUID receptionistStaffId = api.addMember(owner, salonId, "RECEPTIONIST", receptionist);
		String staff = "/api/v1/salons/" + salonId + "/staff/";

		api.put(stylist.accessToken(), staff + stylistStaffId,
				"{\"displayName\": \"Stylist\", \"role\": \"OWNER\", \"bookable\": true}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_PERMISSION"));
		api.put(receptionist.accessToken(), staff + receptionistStaffId,
				"{\"displayName\": \"Desk\", \"role\": \"MANAGER\", \"bookable\": false}")
			.andExpect(status().isForbidden());
		// Not even their own profile text: self-service profiles are not part of Phase 2.
		api.put(stylist.accessToken(), staff + stylistStaffId,
				"{\"displayName\": \"Renamed\", \"role\": \"STAFF\", \"bookable\": true}")
			.andExpect(status().isForbidden());

		api.get(stylist.accessToken(), "/api/v1/me/salons")
			.andExpect(jsonPath("$[?(@.salonId == '%s')].role".formatted(salonId)).value("STAFF"));
	}

	@Test
	void aSalonAlwaysKeepsAnOwner() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser coOwner = api.signUp("Co-owner");
		UUID salonId = api.createSalon(owner, "Last Owner");
		String staff = "/api/v1/salons/" + salonId + "/staff";
		String ownerStaffId = read(api.get(owner.accessToken(), staff), "$[0].id");

		api.put(owner.accessToken(), staff + "/" + ownerStaffId,
				"{\"displayName\": \"Owner\", \"role\": \"MANAGER\", \"bookable\": false}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAST_OWNER"));
		api.post(owner.accessToken(), staff + "/" + ownerStaffId + "/deactivate", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAST_OWNER"));

		api.addMember(owner, salonId, "OWNER", coOwner);
		api.put(owner.accessToken(), staff + "/" + ownerStaffId,
				"{\"displayName\": \"Owner\", \"role\": \"MANAGER\", \"bookable\": false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("MANAGER"));
	}

	@Test
	void deactivatedMembersLoseAccess() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Deactivation");
		UUID stylistStaffId = api.addMember(owner, salonId, "STAFF", stylist);

		api.get(stylist.accessToken(), "/api/v1/salons/" + salonId).andExpect(status().isOk());
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + stylistStaffId + "/deactivate", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));

		api.get(stylist.accessToken(), "/api/v1/salons/" + salonId)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SALON_NOT_FOUND"));
	}

	@Test
	void staffCanOnlyBeAssignedToBranchesOfTheirSalon() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser otherOwner = api.signUp("Other Owner");
		UUID salonId = api.createSalon(owner, "Own Branches");
		UUID foreignBranch = api.createBranch(otherOwner, api.createSalon(otherOwner, "Foreign"));

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "X", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(foreignBranch))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_IN_SALON"));
	}

}
