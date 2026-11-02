package com.salon.salons;

import java.util.List;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.RecordingMessagingConfiguration.RecordingEmailGateway;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit records for sensitive salon operations, and transactional behaviour when email delivery
 * fails.
 */
@IntegrationTest
class SalonAuditAndMessagingIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RecordingEmailGateway emails;

	record AuditRow(String action, UUID actor, String entityId, String details) {
	}

	@Test
	void sensitiveSalonOperationsAreAuditedWithActorAndSalon() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		TestUser stylist = api.signUp("Stylist");
		UUID salonId = api.createSalon(owner, "Audited Salon");
		String salon = "/api/v1/salons/" + salonId;
		String adminSalon = "/api/v1/admin/salons/" + salonId;
		UUID branchId = api.createBranch(owner, salonId);
		UUID staffId = api.addMember(owner, salonId, "STAFF", stylist);
		api.put(owner.accessToken(), salon + "/staff/" + staffId,
				"{\"displayName\": \"Stylist\", \"role\": \"RECEPTIONIST\", \"bookable\": false}");
		api.post(owner.accessToken(), salon + "/staff/" + staffId + "/deactivate", null);
		api.post(owner.accessToken(), salon + "/branches/" + branchId + "/deactivate", null);
		api.post(owner.accessToken(), salon + "/branches/" + branchId + "/activate", null);
		api.post(owner.accessToken(), salon + "/submit-for-review", null);
		api.post(admin.accessToken(), adminSalon + "/reject", "{\"note\": \"Add photos\"}");
		api.post(owner.accessToken(), salon + "/submit-for-review", null);
		api.post(admin.accessToken(), adminSalon + "/approve", null);
		api.post(admin.accessToken(), adminSalon + "/suspend", "{\"note\": \"Complaint\"}");
		api.post(admin.accessToken(), adminSalon + "/reactivate", null);

		List<AuditRow> rows = auditRows(salonId);

		// Order-independent: rows written in the same request can share a timestamp.
		assertThat(rows).extracting(AuditRow::action, AuditRow::actor)
			.containsExactlyInAnyOrder(tuple("salons.salon.created", owner.id()),
					tuple("salons.branch.created", owner.id()),
					tuple("salons.staff.created", owner.id()), tuple("salons.staff.invited", owner.id()),
					tuple("salons.invitation.accepted", stylist.id()), tuple("salons.staff.updated", owner.id()),
					tuple("salons.staff.deactivated", owner.id()), tuple("salons.branch.deactivated", owner.id()),
					tuple("salons.branch.activated", owner.id()), tuple("salons.salon.submitted", owner.id()),
					tuple("salons.salon.rejected", admin.id()), tuple("salons.salon.submitted", owner.id()),
					tuple("salons.salon.approved", admin.id()), tuple("salons.salon.suspended", admin.id()),
					tuple("salons.salon.reactivated", admin.id()));
		assertThat(rows).filteredOn(row -> row.action().equals("salons.salon.rejected"))
			.extracting(AuditRow::details)
			.containsExactly("Add photos");
		assertThat(rows).filteredOn(row -> row.action().equals("salons.staff.updated"))
			.extracting(AuditRow::entityId, AuditRow::details)
			.containsExactly(tuple(staffId.toString(), "RECEPTIONIST"));
	}

	@Test
	void auditRecordsNeverContainInvitationTokens() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "No Secrets");
		api.addMember(owner, salonId, "STAFF", invitee);
		String token = api.lastInvitationTokenSentTo(invitee.email());

		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_log
				WHERE details LIKE ? OR entity_id LIKE ? OR user_agent LIKE ?
				""", Integer.class, "%" + token + "%", "%" + token + "%", "%" + token + "%")).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_invitation WHERE token_hash = ?", Integer.class,
				token))
			.as("tokens are stored only as hashes")
			.isZero();
	}

	@Test
	void failedInvitationEmailRollsBackTheWholeStaffCreation() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonId = api.createSalon(owner, "Email Down");
		String unreachable = TestApi.uniqueEmail();
		emails.failDeliveryTo(unreachable);
		try {
			api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
					{"displayName": "New", "role": "STAFF", "bookable": true, "inviteEmail": "%s"}
					""".formatted(unreachable))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		}
		finally {
			emails.restoreDeliveryTo(unreachable);
		}

		api.get(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff").andExpect(jsonPath("$", hasSize(1)));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_invitation WHERE salon_id = ?", Integer.class,
				salonId))
			.isZero();
		assertThat(auditRows(salonId)).extracting(AuditRow::action)
			.doesNotContain("salons.staff.created", "salons.staff.invited");
	}

	@Test
	void failedResendKeepsThePreviousInvitationValid() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser invitee = api.signUp("Invitee");
		UUID salonId = api.createSalon(owner, "Resend Fails");
		String staffId = read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Invitee", "role": "STAFF", "bookable": true, "inviteEmail": "%s"}
				""".formatted(invitee.email())), "$.id");
		String firstToken = api.lastInvitationTokenSentTo(invitee.email());

		emails.failDeliveryTo(invitee.email());
		try {
			api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId + "/invitations",
					"{\"email\": \"%s\"}".formatted(invitee.email()))
				.andExpect(status().isInternalServerError());
		}
		finally {
			emails.restoreDeliveryTo(invitee.email());
		}

		// The revocation of the first invitation was rolled back together with the failed resend.
		api.post(invitee.accessToken(), "/api/v1/me/invitations/accept", "{\"token\": \"%s\"}".formatted(firstToken))
			.andExpect(status().isOk());
	}

	private List<AuditRow> auditRows(UUID salonId) {
		return jdbc.query("""
				SELECT action, actor_user_id, entity_id, details FROM audit_log
				WHERE salon_id = ? ORDER BY occurred_at, id
				""", (rs, row) -> new AuditRow(rs.getString("action"), rs.getObject("actor_user_id", UUID.class),
				rs.getString("entity_id"), rs.getString("details")), salonId);
	}

}
