package com.salon.salons;

import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.salon.TestApi.branchJson;
import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class SalonLifecycleIntegrationTests {

	@Autowired
	TestApi api;

	@Test
	void creatorBecomesOwnerOfANewDraftSalon() throws Exception {
		TestUser owner = api.signUp("Ayşe Yılmaz");

		String salonId = read(api.post(owner.accessToken(), "/api/v1/salons", """
				{"name": "Ayşe Kuaför", "description": "  Women's hairdresser  "}
				""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.description").value("Women's hairdresser")), "$.id");

		api.get(owner.accessToken(), "/api/v1/me/salons")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.salonId == '%s')].role".formatted(salonId)).value(hasItem("OWNER")))
			.andExpect(jsonPath("$[?(@.salonId == '%s')].permissions[*]".formatted(salonId))
				.value(hasItem("BILLING_MANAGE")));
		api.get(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff")
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].displayName").value("Ayşe Yılmaz"))
			.andExpect(jsonPath("$[0].linked").value(true))
			.andExpect(jsonPath("$[0].bookable").value(false));
	}

	@Test
	void slugsAreTransliteratedAndMadeUnique() throws Exception {
		TestUser owner = api.signUp("Owner");
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String name = "Çiçek Işıl Güzellik " + suffix;

		String first = read(api.post(owner.accessToken(), "/api/v1/salons", "{\"name\": \"%s\"}".formatted(name)),
				"$.slug");
		String second = read(api.post(owner.accessToken(), "/api/v1/salons", "{\"name\": \"%s\"}".formatted(name)),
				"$.slug");

		assertThat(first).isEqualTo("cicek-isil-guzellik-" + suffix);
		assertThat(second).isEqualTo(first + "-2");
	}

	@Test
	void branchGetsDefaultsAndProvinceName() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonId = api.createSalon(owner, "Branch Defaults");

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/branches", branchJson("Moda Şubesi", true))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.slug").value("moda-subesi"))
			.andExpect(jsonPath("$.timeZone").value("Europe/Istanbul"))
			.andExpect(jsonPath("$.address.provinceName").value("İstanbul"))
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.bookingPolicy.autoConfirm").value(true))
			.andExpect(jsonPath("$.bookingPolicy.slotIntervalMinutes").value(15));
	}

	@Test
	void branchInputIsValidated() throws Exception {
		TestUser owner = api.signUp("Owner");
		String branches = "/api/v1/salons/" + api.createSalon(owner, "Validation") + "/branches";

		api.post(owner.accessToken(), branches, branchJson("X", true).replace("\"provinceId\": 34", "\"provinceId\": 99"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		api.post(owner.accessToken(), branches,
				branchJson("X", true).replace("Europe/Istanbul", "UTC+3"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_TIME_ZONE"));
		api.post(owner.accessToken(), branches,
				branchJson("X", true).replace("\"longitude\": 29.0290,", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_COORDINATES"));
		String offGridPolicy = """
				"bookingPolicy": {"autoConfirm": true, "slotIntervalMinutes": 7, "minNoticeMinutes": 0,
				  "maxAdvanceDays": 30, "cancellationCutoffMinutes": 60, "pendingConfirmationTimeoutMinutes": 60},
				""";
		api.post(owner.accessToken(), branches,
				branchJson("X", true).replace("\"timeZone\"", offGridPolicy + "\"timeZone\""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BOOKING_POLICY"));
	}

	@Test
	void submissionNeedsAnActiveBranchWithAMapLocation() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonId = api.createSalon(owner, "Review Ready");
		String submit = "/api/v1/salons/" + salonId + "/submit-for-review";

		api.post(owner.accessToken(), submit, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_NOT_READY_FOR_REVIEW"));

		String branchId = read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/branches",
				branchJson("No Location", false)), "$.id");
		api.post(owner.accessToken(), submit, null).andExpect(status().isConflict());

		api.put(owner.accessToken(), "/api/v1/salons/" + salonId + "/branches/" + branchId,
				branchJson("With Location", true))
			.andExpect(status().isOk());
		api.post(owner.accessToken(), submit, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
	}

	@Test
	void adminReviewApprovalSuspensionAndReactivation() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		UUID salonId = api.createSalon(owner, "Reviewed Salon");
		api.createBranch(owner, salonId);
		String salon = "/api/v1/salons/" + salonId;
		String adminSalon = "/api/v1/admin/salons/" + salonId;

		api.post(owner.accessToken(), salon + "/submit-for-review", null).andExpect(status().isOk());
		// Newest first: other tests leave pending salons behind, so the first page must not depend on them.
		api.get(admin.accessToken(), "/api/v1/admin/salons?status=PENDING_REVIEW&sort=createdAt,desc&size=100")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].id", hasItem(salonId.toString())))
			.andExpect(jsonPath("$.page.size").value(100));

		api.post(admin.accessToken(), adminSalon + "/reject", "{\"note\": \"Please add a phone number\"}")
			.andExpect(jsonPath("$.status").value("DRAFT"));
		api.get(owner.accessToken(), salon).andExpect(jsonPath("$.reviewNote").value("Please add a phone number"));

		api.post(owner.accessToken(), salon + "/submit-for-review", null).andExpect(status().isOk());
		api.post(admin.accessToken(), adminSalon + "/approve", null).andExpect(jsonPath("$.status").value("ACTIVE"));

		api.post(admin.accessToken(), adminSalon + "/suspend", "{\"note\": \"Under investigation\"}")
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		api.put(owner.accessToken(), salon, "{\"name\": \"Renamed\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
		api.post(owner.accessToken(), salon + "/branches", branchJson("Blocked", true))
			.andExpect(status().isConflict());

		api.post(admin.accessToken(), adminSalon + "/reactivate", null)
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		api.put(owner.accessToken(), salon, "{\"name\": \"Renamed\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renamed"));
	}

	@Test
	void adminTransitionsRespectTheLifecycle() throws Exception {
		TestUser owner = api.signUp("Owner");
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		UUID salonId = api.createSalon(owner, "Draft Salon");

		String salon = "/api/v1/salons/" + salonId;
		String adminSalon = "/api/v1/admin/salons/" + salonId;

		api.post(admin.accessToken(), adminSalon + "/approve", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_SALON_STATUS"));
		api.post(admin.accessToken(), adminSalon + "/suspend", "{\"note\": \"x\"}").andExpect(status().isConflict());

		api.createBranch(owner, salonId);
		api.post(owner.accessToken(), salon + "/submit-for-review", null).andExpect(status().isOk());
		api.post(owner.accessToken(), salon + "/submit-for-review", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_SALON_STATUS"));
		api.post(admin.accessToken(), adminSalon + "/reactivate", null).andExpect(status().isConflict());
		api.post(admin.accessToken(), adminSalon + "/reject", "{\"note\": \"  \"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		api.post(admin.accessToken(), adminSalon + "/approve", null).andExpect(status().isOk());
		api.post(admin.accessToken(), adminSalon + "/reject", "{\"note\": \"Too late\"}")
			.andExpect(status().isConflict());
		api.post(owner.accessToken(), salon + "/submit-for-review", null).andExpect(status().isConflict());
		api.get(owner.accessToken(), salon).andExpect(jsonPath("$.status").value("ACTIVE"));

		api.post(admin.accessToken(), "/api/v1/admin/salons/" + UUID.randomUUID() + "/approve", null)
			.andExpect(status().isNotFound());
		api.get(admin.accessToken(), "/api/v1/admin/salons?sort=doesNotExist")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SORT"));
	}

	@Test
	void adminEndpointsAreForPlatformAdminsOnly() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonId = api.createSalon(owner, "Not Admin");

		api.get(owner.accessToken(), "/api/v1/admin/salons").andExpect(status().isForbidden());
		api.post(owner.accessToken(), "/api/v1/admin/salons/" + salonId + "/approve", null)
			.andExpect(status().isForbidden());
	}

	@Test
	void provincesArePublic() throws Exception {
		api.get(null, "/api/v1/public/provinces")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(81)))
			.andExpect(jsonPath("$[33].id").value(34))
			.andExpect(jsonPath("$[33].name").value("İstanbul"));
	}

}
