package com.salon.salons;

import java.util.List;
import java.util.UUID;

import com.salon.Concurrently;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A salon must always keep at least one active owner, also when the last two owners act at the
 * same moment. Each scenario is repeated because races do not show up on every run.
 */
@IntegrationTest
class OwnershipConcurrencyIntegrationTests {

	private static final int ROUNDS = 5;

	private static final String DEMOTE_TO_MANAGER = """
			{"displayName": "Former Owner", "role": "MANAGER", "bookable": false}
			""";

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	record TwoOwners(UUID salonId, TestUser first, UUID firstStaffId, TestUser second, UUID secondStaffId) {

		String staff(UUID staffId) {
			return "/api/v1/salons/" + salonId + "/staff/" + staffId;
		}

	}

	@Test
	void lastTwoOwnersCannotBothDemoteThemselves() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			TwoOwners owners = twoOwners();

			List<Integer> statuses = Concurrently.run(
					() -> status(owners.first(), "PUT", owners.staff(owners.firstStaffId()), DEMOTE_TO_MANAGER),
					() -> status(owners.second(), "PUT", owners.staff(owners.secondStaffId()), DEMOTE_TO_MANAGER));

			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
			assertThat(activeLinkedOwners(owners.salonId())).isEqualTo(1);
		}
	}

	@Test
	void lastTwoOwnersCannotDeactivateEachOther() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			TwoOwners owners = twoOwners();

			List<Integer> statuses = Concurrently.run(
					() -> status(owners.first(), "POST", owners.staff(owners.secondStaffId()) + "/deactivate", null),
					() -> status(owners.second(), "POST", owners.staff(owners.firstStaffId()) + "/deactivate", null));

			// The loser is either refused by the owner rule (409) or was already removed (404).
			assertThat(statuses).containsOnlyOnce(200);
			assertThat(statuses).anyMatch(status -> status == 404 || status == 409);
			assertThat(activeLinkedOwners(owners.salonId())).isEqualTo(1);
		}
	}

	@Test
	void demotionAndDeactivationRacingStillLeaveAnOwner() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			TwoOwners owners = twoOwners();

			List<Integer> statuses = Concurrently.run(
					() -> status(owners.first(), "PUT", owners.staff(owners.firstStaffId()), DEMOTE_TO_MANAGER),
					() -> status(owners.second(), "POST", owners.staff(owners.secondStaffId()) + "/deactivate", null));

			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
			assertThat(activeLinkedOwners(owners.salonId())).isEqualTo(1);
		}
	}

	private TwoOwners twoOwners() throws Exception {
		TestUser first = api.signUp("First Owner");
		TestUser second = api.signUp("Second Owner");
		UUID salonId = api.createSalon(first, "Two Owners");
		UUID secondStaffId = api.addMember(first, salonId, "OWNER", second);
		UUID firstStaffId = jdbc.queryForObject("SELECT id FROM staff_member WHERE salon_id = ? AND user_id = ?",
				UUID.class, salonId, first.id());
		return new TwoOwners(salonId, first, firstStaffId, second, secondStaffId);
	}

	private int status(TestUser user, String method, String path, String body) throws Exception {
		return api.perform(org.springframework.http.HttpMethod.valueOf(method), user.accessToken(), path, body)
			.andReturn()
			.getResponse()
			.getStatus();
	}

	private int activeLinkedOwners(UUID salonId) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM staff_member
				WHERE salon_id = ? AND role = 'OWNER' AND status = 'ACTIVE' AND user_id IS NOT NULL
				""", Integer.class, salonId);
	}

}
