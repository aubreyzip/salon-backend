package com.salon.salons;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import com.salon.Concurrently;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Invitation expiry, revocation, acceptance by new and existing users, suspension and
 * concurrent acceptance. Basic acceptance, email binding, single use and re-sending are covered
 * in {@link StaffIntegrationTests}; expiry boundaries in {@code StaffInvitationTest}.
 */
@IntegrationTest
class InvitationLifecycleIntegrationTests {

	private static final String ACCEPT = "/api/v1/me/invitations/accept";

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void invitationsExpireAfterSevenDays() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "Expiring Invites");
		String staffId = invite(owner, salonId, invitee.email());
		String token = api.lastInvitationTokenSentTo(invitee.email());

		Duration validity = jdbc.queryForObject(
				"SELECT EXTRACT(EPOCH FROM expires_at - created_at)::bigint FROM staff_invitation WHERE staff_id = ?",
				(rs, row) -> Duration.ofSeconds(rs.getLong(1)), UUID.fromString(staffId));
		assertThat(validity).isEqualTo(Duration.ofDays(7));

		jdbc.update("UPDATE staff_invitation SET expires_at = now() - interval '1 second' WHERE staff_id = ?",
				UUID.fromString(staffId));

		api.post(invitee.accessToken(), ACCEPT, token(token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
		api.get(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff")
			.andExpect(jsonPath("$[?(@.id == '%s')].invitationPending".formatted(staffId)).value(false));
	}

	@Test
	void deactivatingTheStaffMemberRevokesTheOpenInvitation() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "Revoked Invites");
		String staffId = invite(owner, salonId, invitee.email());
		String token = api.lastInvitationTokenSentTo(invitee.email());

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId + "/deactivate", null)
			.andExpect(status().isOk());

		api.post(invitee.accessToken(), ACCEPT, token(token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
		assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM staff_invitation WHERE staff_id = ?",
				Boolean.class, UUID.fromString(staffId)))
			.isTrue();
	}

	@Test
	void cancellingAnInvitationInvalidatesTheTokenButKeepsTheStaffMember() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "Cancelled Invites");
		String staffId = invite(owner, salonId, invitee.email());
		String token = api.lastInvitationTokenSentTo(invitee.email());
		String invitations = "/api/v1/salons/" + salonId + "/staff/" + staffId + "/invitations";

		api.perform(HttpMethod.DELETE, owner.accessToken(), invitations, null).andExpect(status().isNoContent());

		api.post(invitee.accessToken(), ACCEPT, token(token))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
		api.get(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff")
			.andExpect(jsonPath("$[?(@.id == '%s')].active".formatted(staffId)).value(true))
			.andExpect(jsonPath("$[?(@.id == '%s')].invitationPending".formatted(staffId)).value(false));
		assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE salon_id = ? AND entity_id = ?",
				String.class, salonId, staffId))
			.containsOnlyOnce("salons.staff.invitation_cancelled");

		// Idempotent: nothing open, nothing audited.
		api.perform(HttpMethod.DELETE, owner.accessToken(), invitations, null).andExpect(status().isNoContent());
		assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE salon_id = ? AND entity_id = ?",
				String.class, salonId, staffId))
			.containsOnlyOnce("salons.staff.invitation_cancelled");
	}

	@Test
	void managersCannotCancelInvitationsForOwnerProfiles() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser manager = api.signUp("Manager");
		UUID salonId = api.createSalon(owner, "Owner Invite Cancel");
		api.addMember(owner, salonId, "MANAGER", manager);
		String ownerProfile = read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Co-owner", "role": "OWNER", "bookable": false, "inviteEmail": "%s"}
				""".formatted(TestApi.uniqueEmail())), "$.id");

		api.perform(HttpMethod.DELETE, manager.accessToken(),
				"/api/v1/salons/" + salonId + "/staff/" + ownerProfile + "/invitations", null)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ROLE_CHANGE_NOT_ALLOWED"));
	}

	@Test
	void personWithoutAnAccountCanRegisterAfterBeingInvitedAndThenAccept() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonId = api.createSalon(owner, "Invite Before Signup");
		String email = TestApi.uniqueEmail();
		invite(owner, salonId, email);
		String token = api.lastInvitationTokenSentTo(email);

		TestUser newcomer = api.signUp("Newcomer", email);

		api.post(newcomer.accessToken(), ACCEPT, token(token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("STAFF"));
	}

	@Test
	void anActiveMemberCannotAcceptASecondMembershipInTheSameSalon() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Second Membership");
		UUID existingStaffId = api.addMember(owner, salonId, "STAFF", stylist);

		invite(owner, salonId, stylist.email());
		api.post(stylist.accessToken(), ACCEPT, token(api.lastInvitationTokenSentTo(stylist.email())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_A_MEMBER"));

		api.get(stylist.accessToken(), "/api/v1/me/salons")
			.andExpect(jsonPath("$[?(@.salonId == '%s')].staffId".formatted(salonId))
				.value(existingStaffId.toString()));
	}

	@Test
	void invitationsCannotBeAcceptedWhileTheSalonIsSuspended() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "Suspended Invites");
		api.createBranch(owner, salonId);
		invite(owner, salonId, invitee.email());
		String token = api.lastInvitationTokenSentTo(invitee.email());
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/submit-for-review", null);
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/approve", null);
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/suspend", "{\"note\": \"Review\"}")
			.andExpect(status().isOk());

		api.post(invitee.accessToken(), ACCEPT, token(token))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));

		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/reactivate", null);
		api.post(invitee.accessToken(), ACCEPT, token(token)).andExpect(status().isOk());
	}

	@Test
	void concurrentAcceptanceOfOneInvitationSucceedsExactlyOnce() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "Double Tap");
		String staffId = invite(owner, salonId, invitee.email());
		String body = token(api.lastInvitationTokenSentTo(invitee.email()));

		List<Callable<Integer>> sameRequestFourTimes = IntStream.range(0, 4)
			.<Callable<Integer>>mapToObj(i -> () -> statusOf(invitee, body))
			.toList();
		List<Integer> statuses = Concurrently.run(sameRequestFourTimes);

		assertThat(statuses).containsOnlyOnce(200);
		assertThat(statuses).allMatch(status -> status == 200 || status == 400 || status == 409);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_member WHERE salon_id = ? AND user_id = ?",
				Integer.class, salonId, invitee.id()))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT user_id FROM staff_member WHERE id = ?", UUID.class,
				UUID.fromString(staffId)))
			.isEqualTo(invitee.id());
	}

	@Test
	void concurrentAcceptanceOfTwoInvitationsToOneSalonCreatesOneMembership() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "Two Invites");
		invite(owner, salonId, invitee.email());
		String first = token(api.lastInvitationTokenSentTo(invitee.email()));
		invite(owner, salonId, invitee.email());
		String second = token(api.lastInvitationTokenSentTo(invitee.email()));

		List<Integer> statuses = Concurrently.run(() -> statusOf(invitee, first), () -> statusOf(invitee, second));

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_member WHERE salon_id = ? AND user_id = ?",
				Integer.class, salonId, invitee.id()))
			.isEqualTo(1);
	}

	/** Creates an unlinked STAFF member and invites {@code email}; returns the staff id. */
	private String invite(TestUser owner, UUID salonId, String email) throws Exception {
		return read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Invited", "role": "STAFF", "bookable": true, "inviteEmail": "%s"}
				""".formatted(email)).andExpect(status().isCreated()), "$.id");
	}

	private int statusOf(TestUser user, String body) throws Exception {
		return api.post(user.accessToken(), ACCEPT, body).andReturn().getResponse().getStatus();
	}

	private static String token(String token) {
		return "{\"token\": \"%s\"}".formatted(token);
	}

}
