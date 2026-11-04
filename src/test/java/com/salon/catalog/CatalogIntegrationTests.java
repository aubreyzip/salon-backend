package com.salon.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog management (ADR-0012): services, sections, branch offerings with overrides, staff skills
 * and the public service list.
 */
@IntegrationTest
class CatalogIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	ServiceCatalog serviceCatalog;

	private TestUser owner;

	private UUID salonId;

	private UUID branchId;

	private String services;

	@BeforeEach
	void createSalon() throws Exception {
		owner = api.signUp("Catalog Owner");
		salonId = api.createSalon(owner, "Catalog Salon");
		branchId = api.createBranch(owner, salonId);
		services = "/api/v1/salons/" + salonId + "/services";
	}

	@Test
	void platformCategoriesArePublic() throws Exception {
		api.get(null, "/api/v1/public/service-categories")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].code").value("HAIRCUT"))
			.andExpect(jsonPath("$[?(@.code == 'OTHER')].name").value(contains("Diğer")));
	}

	@Test
	void servicesGetDefaultsAndTurkishLira() throws Exception {
		api.post(owner.accessToken(), services, TestApi.serviceJson("Kadın Saç Kesimi", 45))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.bufferMinutes").value(0))
			.andExpect(jsonPath("$.price").value(450.0))
			.andExpect(jsonPath("$.currency").value("TRY"))
			.andExpect(jsonPath("$.priceFrom").value(false))
			.andExpect(jsonPath("$.onlineBookable").value(true))
			.andExpect(jsonPath("$.archived").value(false))
			.andExpect(jsonPath("$.offerings", hasSize(0)));
	}

	@Test
	void invalidServicesAreRejected() throws Exception {
		api.post(owner.accessToken(), services, """
				{"categoryId": 1000, "name": "Unknown", "durationMinutes": 30, "price": 1}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_SERVICE_CATEGORY"));
		api.post(owner.accessToken(), services, """
				{"categoryId": 1, "name": "Free", "durationMinutes": 30, "price": -1}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		api.post(owner.accessToken(), services, """
				{"categoryId": 1, "name": "Too long", "durationMinutes": 721, "price": 1}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		api.post(owner.accessToken(), services, """
				{"categoryId": 1, "name": "Fractions", "durationMinutes": 30, "price": 1.005}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void sectionsOfAnotherSalonCannotBeUsed() throws Exception {
		TestUser otherOwner = api.signUp("Other Owner");
		UUID otherSalon = api.createSalon(otherOwner, "Other Catalog Salon");
		String foreignSection = read(api.post(otherOwner.accessToken(), "/api/v1/salons/" + otherSalon
				+ "/service-sections", "{\"name\": \"Saç\"}").andExpect(status().isCreated()), "$.id");

		api.post(owner.accessToken(), services, """
				{"categoryId": 1, "sectionId": "%s", "name": "Cut", "durationMinutes": 30, "price": 1}
				""".formatted(foreignSection))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_SERVICE_SECTION"));
	}

	@Test
	void deletingASectionKeepsItsServices() throws Exception {
		String sections = "/api/v1/salons/" + salonId + "/service-sections";
		String sectionId = read(api.post(owner.accessToken(), sections, "{\"name\": \"Saç\", \"sortOrder\": 1}"), "$.id");
		String serviceId = read(api.post(owner.accessToken(), services, """
				{"categoryId": 1, "sectionId": "%s", "name": "Cut", "durationMinutes": 30, "price": 1}
				""".formatted(sectionId)).andExpect(status().isCreated()), "$.id");

		api.delete(owner.accessToken(), sections + "/" + sectionId).andExpect(status().isNoContent());

		api.get(owner.accessToken(), services + "/" + serviceId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sectionId").value(nullValue()));
		api.get(owner.accessToken(), sections).andExpect(jsonPath("$", hasSize(0)));
		api.delete(owner.accessToken(), sections + "/" + sectionId)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SERVICE_SECTION_NOT_FOUND"));
	}

	@Test
	void branchOfferingsOverrideServiceDefaults() throws Exception {
		UUID serviceId = api.createService(owner, salonId, "Fön", 30);
		String offering = services + "/" + serviceId + "/branches/" + branchId;

		api.put(owner.accessToken(), offering, "{\"priceOverride\": 500.50, \"bufferOverrideMinutes\": 10}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.offerings[0].branchId").value(branchId.toString()))
			.andExpect(jsonPath("$.offerings[0].price").value(500.5))
			.andExpect(jsonPath("$.offerings[0].durationMinutes").value(30))
			.andExpect(jsonPath("$.offerings[0].durationOverrideMinutes").value(nullValue()))
			.andExpect(jsonPath("$.offerings[0].bufferMinutes").value(10));
		// Changing the overrides updates the same offering.
		api.put(owner.accessToken(), offering, "{\"durationOverrideMinutes\": 40}")
			.andExpect(jsonPath("$.offerings", hasSize(1)))
			.andExpect(jsonPath("$.offerings[0].price").value(450.0))
			.andExpect(jsonPath("$.offerings[0].durationMinutes").value(40));

		api.delete(owner.accessToken(), offering).andExpect(status().isOk()).andExpect(jsonPath("$.offerings", hasSize(0)));
		api.delete(owner.accessToken(), offering).andExpect(status().isOk());
	}

	@Test
	void servicesCanOnlyBeOfferedAtTheSalonsOwnBranches() throws Exception {
		TestUser otherOwner = api.signUp("Other Owner");
		UUID foreignBranch = api.createBranch(otherOwner, api.createSalon(otherOwner, "Foreign Branch Salon"));
		UUID serviceId = api.createService(owner, salonId, "Fön", 30);

		api.put(owner.accessToken(), services + "/" + serviceId + "/branches/" + foreignBranch, "{}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
	}

	@Test
	void archivedServicesAreHiddenAndCannotBeOfferedUntilRestored() throws Exception {
		UUID serviceId = api.createService(owner, salonId, "Perma", 120);
		String service = services + "/" + serviceId;

		api.post(owner.accessToken(), service + "/archive", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.archived").value(true));
		api.get(owner.accessToken(), services).andExpect(jsonPath("$[?(@.id == '%s')]".formatted(serviceId), hasSize(0)));
		api.get(owner.accessToken(), services + "?includeArchived=true")
			.andExpect(jsonPath("$[?(@.id == '%s')]".formatted(serviceId), hasSize(1)));
		api.put(owner.accessToken(), service + "/branches/" + branchId, "{}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SERVICE_ARCHIVED"));

		api.post(owner.accessToken(), service + "/restore", null).andExpect(jsonPath("$.archived").value(false));
		api.put(owner.accessToken(), service + "/branches/" + branchId, "{}").andExpect(status().isOk());
	}

	@Test
	void staffSkillsAreReplacedAsAWhole() throws Exception {
		UUID cut = api.createService(owner, salonId, "Cut", 30);
		UUID color = api.createService(owner, salonId, "Color", 90);
		String staffId = read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff",
				"{\"displayName\": \"Ayşe\", \"role\": \"STAFF\", \"bookable\": true}"), "$.id");
		String skills = "/api/v1/salons/" + salonId + "/staff/" + staffId + "/skills";

		api.put(owner.accessToken(), skills, """
				{"skills": [{"serviceId": "%s"}, {"serviceId": "%s", "durationOverrideMinutes": 120}]}
				""".formatted(cut, color)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
		api.put(owner.accessToken(), skills, """
				{"skills": [{"serviceId": "%s", "durationOverrideMinutes": 25}]}
				""".formatted(cut)).andExpect(status().isOk());

		api.get(owner.accessToken(), skills)
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].serviceId").value(cut.toString()))
			.andExpect(jsonPath("$[0].durationOverrideMinutes").value(25));
	}

	@Test
	void invalidSkillsAreRejected() throws Exception {
		UUID cut = api.createService(owner, salonId, "Cut", 30);
		TestUser otherOwner = api.signUp("Other Owner");
		UUID foreignService = api.createService(otherOwner, api.createSalon(otherOwner, "Skill Salon"), "Cut", 30);
		String staffId = read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff",
				"{\"displayName\": \"Ayşe\", \"role\": \"STAFF\", \"bookable\": true}"), "$.id");
		String skills = "/api/v1/salons/" + salonId + "/staff/" + staffId + "/skills";

		api.put(owner.accessToken(), skills, "{\"skills\": [{\"serviceId\": \"%s\"}]}".formatted(foreignService))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SERVICE_NOT_IN_SALON"));
		api.put(owner.accessToken(), skills, """
				{"skills": [{"serviceId": "%s"}, {"serviceId": "%s"}]}
				""".formatted(cut, cut))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DUPLICATE_SERVICE"));
		api.put(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + UUID.randomUUID() + "/skills",
				"{\"skills\": []}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
	}

	@Test
	void requirementsUseTheMostSpecificDurationAndTheLargestBuffer() throws Exception {
		UUID cut = api.createService(owner, salonId, "Cut", 30);
		UUID color = UUID.fromString(read(api.post(owner.accessToken(), services, """
				{"categoryId": 2, "name": "Color", "durationMinutes": 90, "bufferMinutes": 15, "price": 900}
				""").andExpect(status().isCreated()), "$.id"));
		api.put(owner.accessToken(), services + "/" + cut + "/branches/" + branchId,
				"{\"durationOverrideMinutes\": 40, \"bufferOverrideMinutes\": 20}");
		api.put(owner.accessToken(), services + "/" + color + "/branches/" + branchId, "{}");
		String staffPath = "/api/v1/salons/" + salonId + "/staff";
		String both = read(api.post(owner.accessToken(), staffPath,
				"{\"displayName\": \"Both\", \"role\": \"STAFF\", \"bookable\": true}"), "$.id");
		String cutOnly = read(api.post(owner.accessToken(), staffPath,
				"{\"displayName\": \"Cut only\", \"role\": \"STAFF\", \"bookable\": true}"), "$.id");
		api.put(owner.accessToken(), staffPath + "/" + both + "/skills", """
				{"skills": [{"serviceId": "%s"}, {"serviceId": "%s", "durationOverrideMinutes": 75}]}
				""".formatted(cut, color)).andExpect(status().isOk());
		api.put(owner.accessToken(), staffPath + "/" + cutOnly + "/skills",
				"{\"skills\": [{\"serviceId\": \"%s\"}]}".formatted(cut)).andExpect(status().isOk());

		BookingRequirements requirements = serviceCatalog.requirements(salonId, branchId,
				List.of(cut, color), true);

		// Staff override 75 for color + branch override 40 for cut; buffer max(20, 15).
		assertThat(requirements.durationMinutesByStaff())
			.containsExactlyEntriesOf(Map.of(UUID.fromString(both), 115));
		assertThat(requirements.bufferMinutes()).isEqualTo(20);
		assertThat(requirements.services())
			.extracting(BookingRequirements.RequiredService::durationMinutes)
			.containsExactly(40, 90);
	}

	@Test
	void publicServicesAreThoseOfferedOnlineAtActiveBranchesOfActiveSalons() throws Exception {
		UUID offeredOnline = api.createService(owner, salonId, "Online", 30);
		UUID inStoreOnly = UUID.fromString(read(api.post(owner.accessToken(), services, """
				{"categoryId": 1, "name": "Consultation", "durationMinutes": 30, "price": 0, "onlineBookable": false}
				""").andExpect(status().isCreated()), "$.id"));
		api.createService(owner, salonId, "Not offered here", 30);
		UUID archived = api.createService(owner, salonId, "Archived", 30);
		for (UUID serviceId : new UUID[] { offeredOnline, inStoreOnly, archived }) {
			api.put(owner.accessToken(), services + "/" + serviceId + "/branches/" + branchId,
					serviceId.equals(offeredOnline) ? "{\"priceOverride\": 300}" : "{}")
				.andExpect(status().isOk());
		}
		api.post(owner.accessToken(), services + "/" + archived + "/archive", null).andExpect(status().isOk());
		String publicServices = "/api/v1/public/branches/" + branchId + "/services";

		// Not approved yet: the branch is not public.
		api.get(null, publicServices).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));

		api.approveSalon(owner, salonId);
		api.get(null, publicServices)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(offeredOnline.toString()))
			.andExpect(jsonPath("$[0].price").value(300.0))
			.andExpect(jsonPath("$[0].currency").value("TRY"));

		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/branches/" + branchId + "/deactivate", null)
			.andExpect(status().isOk());
		api.get(null, publicServices).andExpect(status().isNotFound());
	}

}
