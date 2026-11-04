package com.salon.scheduling;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static com.salon.TestApi.read;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public availability end to end (ADR-0012): catalog, staff, branch calendar and working hours
 * combined. Fixture: an approved salon, branch open 09:00–18:00 daily with the default policy
 * (15-minute grid, 60 minutes notice, 60 days advance), a 60-minute "Cut" offered online, and Ayşe,
 * skilled and working 10:00–14:00 daily. The queried day is three days ahead.
 */
@IntegrationTest
class AvailabilityIntegrationTests {

	private static final LocalDate DAY = LocalDate.now(ZoneId.of("Europe/Istanbul")).plusDays(3);

	private static final String[] CUT_STARTS = { "10:00:00", "10:15:00", "10:30:00", "10:45:00", "11:00:00",
			"11:15:00", "11:30:00", "11:45:00", "12:00:00", "12:15:00", "12:30:00", "12:45:00", "13:00:00" };

	@Autowired
	TestApi api;

	private TestUser owner;

	private UUID salonId;

	private UUID branchId;

	private UUID cut;

	private UUID ayse;

	private String salon;

	@BeforeEach
	void createBookableSalon() throws Exception {
		owner = api.signUp("Availability Owner");
		salonId = api.createSalon(owner, "Availability Salon");
		branchId = api.createBranch(owner, salonId);
		salon = "/api/v1/salons/" + salonId;
		api.put(owner.accessToken(), salon + "/branches/" + branchId + "/opening-hours", everyDay("09:00", "18:00"))
			.andExpect(status().isOk());
		cut = api.createService(owner, salonId, "Cut", 60);
		offer(cut);
		ayse = createSkilledStaff("Ayşe", cut);
		workingHours(ayse, "10:00", "14:00");
		api.approveSalon(owner, salonId);
	}

	@Test
	void offersStartsWhereASkilledStaffMemberIsWorking() throws Exception {
		availability(DAY, DAY, cut)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.timeZone").value("Europe/Istanbul"))
			.andExpect(jsonPath("$.services[0].durationMinutes").value(60))
			.andExpect(jsonPath("$.staff[*].displayName").value(contains("Ayşe")))
			.andExpect(jsonPath("$.days[0].date").value(DAY.toString()))
			.andExpect(jsonPath("$.days[0].slots[*].localTime").value(contains(CUT_STARTS)))
			.andExpect(jsonPath("$.days[0].slots[*].staffIds[*]").value(everyItem(is(ayse.toString()))));
	}

	@Test
	void everyDayOfTheRangeIsListed() throws Exception {
		availability(DAY, DAY.plusDays(30), cut).andExpect(status().isOk()).andExpect(jsonPath("$.days", hasSize(31)));
	}

	@Test
	void timeOffRemovesStarts() throws Exception {
		api.post(owner.accessToken(), salon + "/staff/" + ayse + "/time-off", """
				{"startAt": "%sT11:00:00+03:00", "endAt": "%sT12:00:00+03:00"}
				""".formatted(DAY, DAY)).andExpect(status().isCreated());

		availability(DAY, DAY, cut).andExpect(jsonPath("$.days[0].slots[*].localTime")
			.value(contains("10:00:00", "12:00:00", "12:15:00", "12:30:00", "12:45:00", "13:00:00")));
	}

	@Test
	void branchClosuresAndSpecialDaysApply() throws Exception {
		String branch = salon + "/branches/" + branchId;
		api.post(owner.accessToken(), branch + "/closures", """
				{"startDate": "%s", "endDate": "%s"}
				""".formatted(DAY, DAY)).andExpect(status().isCreated());
		api.put(owner.accessToken(), branch + "/special-days/" + DAY.plusDays(1), """
				{"intervals": [{"startTime": "11:00", "endTime": "12:30"}]}
				""").andExpect(status().isOk());

		availability(DAY, DAY.plusDays(1), cut)
			.andExpect(jsonPath("$.days[0].slots", hasSize(0)))
			.andExpect(jsonPath("$.days[1].slots[*].localTime").value(contains("11:00:00", "11:15:00", "11:30:00")));
	}

	@Test
	void severalServicesArePerformedBackToBackByOneStaffMember() throws Exception {
		UUID wash = api.createService(owner, salonId, "Wash", 30);
		offer(wash);
		api.put(owner.accessToken(), salon + "/staff/" + ayse + "/skills", """
				{"skills": [{"serviceId": "%s"}, {"serviceId": "%s"}]}
				""".formatted(cut, wash)).andExpect(status().isOk());

		availability(DAY, DAY, cut, wash)
			.andExpect(jsonPath("$.staff[0].durationMinutes").value(90))
			.andExpect(jsonPath("$.days[0].slots[-1].localTime").value("12:30:00"));
	}

	@Test
	void onlyBookableStaffAssignedToTheBranchWithAllSkillsAreOffered() throws Exception {
		UUID wash = api.createService(owner, salonId, "Wash", 30);
		offer(wash);
		UUID mehmet = createSkilledStaff("Mehmet", cut, wash);
		workingHours(mehmet, "15:00", "17:00");

		availability(DAY, DAY, cut, wash).andExpect(jsonPath("$.staff[*].displayName").value(contains("Mehmet")));
		availability(DAY, DAY, cut).andExpect(jsonPath("$.staff", hasSize(2)));
		availability(DAY, DAY, cut).andExpect(jsonPath("$.days[0].slots[*].localTime", hasItem("15:00:00")));
		// Filter by staff member.
		api.get(null, availabilityPath(DAY, DAY, cut) + "&staffId=" + ayse)
			.andExpect(jsonPath("$.staff[*].displayName").value(contains("Ayşe")))
			.andExpect(jsonPath("$.days[0].slots[*].localTime", not(hasItem("15:00:00"))));

		api.put(owner.accessToken(), salon + "/staff/" + mehmet, """
				{"displayName": "Mehmet", "role": "STAFF", "bookable": false, "branchIds": ["%s"]}
				""".formatted(branchId)).andExpect(status().isOk());
		availability(DAY, DAY, cut).andExpect(jsonPath("$.staff[*].displayName").value(contains("Ayşe")));
		api.put(owner.accessToken(), salon + "/staff/" + ayse, """
				{"displayName": "Ayşe", "role": "STAFF", "bookable": true, "branchIds": []}
				""").andExpect(status().isOk());
		availability(DAY, DAY, cut)
			.andExpect(jsonPath("$.staff", hasSize(0)))
			.andExpect(jsonPath("$.days[0].slots", hasSize(0)));
	}

	@Test
	void servicesMustBeOfferedAtTheBranchAndBookableOnline() throws Exception {
		UUID notOffered = api.createService(owner, salonId, "Not offered", 30);
		UUID inStore = UUID.fromString(read(api.post(owner.accessToken(), salon + "/services", """
				{"categoryId": 1, "name": "Consultation", "durationMinutes": 30, "price": 0, "onlineBookable": false}
				""").andExpect(status().isCreated()), "$.id"));
		offer(inStore);

		availability(DAY, DAY, notOffered).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SERVICE_NOT_OFFERED"));
		availability(DAY, DAY, inStore).andExpect(jsonPath("$.code").value("SERVICE_NOT_OFFERED"));
		availability(DAY, DAY, UUID.randomUUID()).andExpect(jsonPath("$.code").value("SERVICE_NOT_OFFERED"));
		api.post(owner.accessToken(), salon + "/services/" + cut + "/archive", null).andExpect(status().isOk());
		availability(DAY, DAY, cut).andExpect(jsonPath("$.code").value("SERVICE_NOT_OFFERED"));
	}

	@Test
	void onlyActiveBranchesOfActiveSalonsArePublic() throws Exception {
		TestUser draftOwner = api.signUp("Draft Owner");
		UUID draftSalon = api.createSalon(draftOwner, "Draft Salon");
		UUID draftBranch = api.createBranch(draftOwner, draftSalon);
		api.get(null, "/api/v1/public/branches/" + draftBranch + "/availability?serviceIds=" + cut + "&from=" + DAY
				+ "&to=" + DAY)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));

		api.post(owner.accessToken(), salon + "/branches/" + branchId + "/deactivate", null).andExpect(status().isOk());
		availability(DAY, DAY, cut).andExpect(status().isNotFound());
	}

	@Test
	void queriesAreLimited() throws Exception {
		availability(DAY, DAY.plusDays(31), cut).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
		availability(DAY, DAY.minusDays(1), cut).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
		availability(DAY, DAY, cut, cut, cut, cut, cut, cut).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SERVICES"));
		api.get(null, "/api/v1/public/branches/" + branchId + "/availability?from=" + DAY + "&to=" + DAY)
			.andExpect(status().isBadRequest());
	}

	private ResultActions availability(LocalDate from, LocalDate to, UUID... serviceIds) throws Exception {
		return api.get(null, availabilityPath(from, to, serviceIds));
	}

	private String availabilityPath(LocalDate from, LocalDate to, UUID... serviceIds) {
		String ids = String.join(",", Arrays.stream(serviceIds).map(UUID::toString).toList());
		return "/api/v1/public/branches/" + branchId + "/availability?serviceIds=" + ids + "&from=" + from + "&to="
				+ to;
	}

	private void offer(UUID serviceId) throws Exception {
		api.put(owner.accessToken(), salon + "/services/" + serviceId + "/branches/" + branchId, "{}")
			.andExpect(status().isOk());
	}

	private UUID createSkilledStaff(String name, UUID... serviceIds) throws Exception {
		UUID staffId = UUID.fromString(read(api.post(owner.accessToken(), salon + "/staff", """
				{"displayName": "%s", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(name, branchId)).andExpect(status().isCreated()), "$.id"));
		String skills = String.join(",",
				Arrays.stream(serviceIds).map(id -> "{\"serviceId\": \"" + id + "\"}").toList());
		api.put(owner.accessToken(), salon + "/staff/" + staffId + "/skills", "{\"skills\": [" + skills + "]}")
			.andExpect(status().isOk());
		return staffId;
	}

	private void workingHours(UUID staffId, String start, String end) throws Exception {
		api.put(owner.accessToken(), salon + "/staff/" + staffId + "/branches/" + branchId + "/working-hours",
				"{\"periods\": [{\"effectiveFrom\": \"2026-01-01\", \"intervals\": " + intervals(start, end) + "}]}")
			.andExpect(status().isOk());
	}

	private static String everyDay(String start, String end) {
		return "{\"intervals\": " + intervals(start, end) + "}";
	}

	private static String intervals(String start, String end) {
		return "[" + String.join(",", Arrays.stream(DayOfWeek.values())
			.map(day -> "{\"dayOfWeek\": \"%s\", \"startTime\": \"%s\", \"endTime\": \"%s\"}".formatted(day, start, end))
			.toList()) + "]";
	}

}
