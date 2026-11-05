package com.salon.scheduling;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.salon.Concurrently;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch calendars, staff working hours and time off (ADR-0012), with the permission scopes of
 * ADR-0010. Each test uses its own salon with branches A and B.
 */
@IntegrationTest
class SchedulingIntegrationTests {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Istanbul"));

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	private TestUser owner;

	private UUID salonId;

	private UUID branchA;

	private UUID branchB;

	private String salon;

	@BeforeEach
	void createSalonWithTwoBranches() throws Exception {
		owner = api.signUp("Scheduling Owner");
		salonId = api.createSalon(owner, "Scheduling Salon");
		branchA = api.createBranch(owner, salonId);
		branchB = api.createBranch(owner, salonId);
		salon = "/api/v1/salons/" + salonId;
	}

	@Test
	void openingHoursAreReplacedAsAWhole() throws Exception {
		String openingHours = salon + "/branches/" + branchA + "/opening-hours";

		api.put(owner.accessToken(), openingHours, """
				{"intervals": [{"dayOfWeek": "TUESDAY", "startTime": "10:00", "endTime": "16:00"},
				               {"dayOfWeek": "MONDAY", "startTime": "13:00", "endTime": "18:00"},
				               {"dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "12:00"}]}
				""").andExpect(status().isOk());

		api.get(owner.accessToken(), openingHours)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.timeZone").value("Europe/Istanbul"))
			.andExpect(jsonPath("$.intervals[*].dayOfWeek").value(contains("MONDAY", "MONDAY", "TUESDAY")))
			.andExpect(jsonPath("$.intervals[0].startTime").value("09:00:00"))
			.andExpect(jsonPath("$.intervals[1].startTime").value("13:00:00"));

		api.put(owner.accessToken(), openingHours, "{\"intervals\": []}").andExpect(status().isOk());
		api.get(owner.accessToken(), openingHours).andExpect(jsonPath("$.intervals", hasSize(0)));
	}

	@Test
	void invalidOpeningHoursAreRejected() throws Exception {
		String openingHours = salon + "/branches/" + branchA + "/opening-hours";

		api.put(owner.accessToken(), openingHours, """
				{"intervals": [{"dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "13:00"},
				               {"dayOfWeek": "MONDAY", "startTime": "12:00", "endTime": "18:00"}]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OVERLAPPING_INTERVALS"));
		api.put(owner.accessToken(), openingHours, """
				{"intervals": [{"dayOfWeek": "MONDAY", "startTime": "22:00", "endTime": "02:00"}]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INTERVAL"));
		api.put(owner.accessToken(), salon + "/branches/" + UUID.randomUUID() + "/opening-hours", "{\"intervals\": []}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
	}

	@Test
	void specialDaysReplaceTheWeeklyHoursOfOneDate() throws Exception {
		LocalDate date = TODAY.plusDays(10);
		String specialDays = salon + "/branches/" + branchA + "/special-days";

		api.put(owner.accessToken(), specialDays + "/" + date, """
				{"intervals": [{"startTime": "10:00", "endTime": "14:00"}], "note": "Arife"}
				""").andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(false));
		api.put(owner.accessToken(), specialDays + "/" + date.plusDays(1), "{\"intervals\": []}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.closed").value(true));

		api.get(owner.accessToken(), specialDays)
			.andExpect(jsonPath("$[*].date").value(contains(date.toString(), date.plusDays(1).toString())))
			.andExpect(jsonPath("$[0].note").value("Arife"));
		api.get(owner.accessToken(), specialDays + "?from=" + date.plusDays(1)).andExpect(jsonPath("$", hasSize(1)));

		api.delete(owner.accessToken(), specialDays + "/" + date).andExpect(status().isNoContent());
		api.delete(owner.accessToken(), specialDays + "/" + date).andExpect(status().isNoContent());
		api.get(owner.accessToken(), specialDays).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void closuresCoverWholeDays() throws Exception {
		String closures = salon + "/branches/" + branchA + "/closures";

		String closureId = read(api.post(owner.accessToken(), closures, """
				{"startDate": "%s", "endDate": "%s", "reason": "Tadilat"}
				""".formatted(TODAY.plusDays(3), TODAY.plusDays(5))).andExpect(status().isCreated()), "$.id");
		api.post(owner.accessToken(), closures, """
				{"startDate": "%s", "endDate": "%s"}
				""".formatted(TODAY.plusDays(5), TODAY.plusDays(3)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

		api.get(owner.accessToken(), closures).andExpect(jsonPath("$[*].id").value(contains(closureId)));
		api.get(owner.accessToken(), closures + "?from=" + TODAY.plusDays(6)).andExpect(jsonPath("$", hasSize(0)));

		api.delete(owner.accessToken(), closures + "/" + closureId).andExpect(status().isNoContent());
		api.delete(owner.accessToken(), closures + "/" + closureId)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CLOSURE_NOT_FOUND"));
	}

	@Test
	void workingHoursNeedAnActiveStaffMemberAssignedToTheBranch() throws Exception {
		UUID staffId = createStaff("Ayşe", branchA);
		String hoursAtB = salon + "/staff/" + staffId + "/branches/" + branchB + "/working-hours";

		api.put(owner.accessToken(), hoursAtB, hours("MONDAY", "09:00", "17:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_NOT_ASSIGNED_TO_BRANCH"));
		// Clearing is always possible.
		api.put(owner.accessToken(), hoursAtB, "{\"periods\": []}").andExpect(status().isOk());

		api.post(owner.accessToken(), salon + "/staff/" + staffId + "/deactivate", null).andExpect(status().isOk());
		api.put(owner.accessToken(), salon + "/staff/" + staffId + "/branches/" + branchA + "/working-hours",
				hours("MONDAY", "09:00", "17:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_INACTIVE"));
	}

	@Test
	void periodsAtOneBranchMustNotOverlapInDates() throws Exception {
		UUID staffId = createStaff("Ayşe", branchA);
		String hoursAtA = salon + "/staff/" + staffId + "/branches/" + branchA + "/working-hours";
		String interval = "[{\"dayOfWeek\": \"MONDAY\", \"startTime\": \"09:00\", \"endTime\": \"17:00\"}]";

		api.put(owner.accessToken(), hoursAtA, """
				{"periods": [{"effectiveFrom": "2026-01-01", "effectiveTo": "2026-12-31", "intervals": %s},
				             {"effectiveFrom": "2026-12-31", "intervals": %s}]}
				""".formatted(interval, interval))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("OVERLAPPING_PERIODS"));

		api.put(owner.accessToken(), hoursAtA, """
				{"periods": [{"effectiveFrom": "2027-01-01", "intervals": %s},
				             {"effectiveFrom": "2026-01-01", "effectiveTo": "2026-12-31", "intervals": %s}]}
				""".formatted(interval, interval))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.periods[*].effectiveFrom").value(contains("2026-01-01", "2027-01-01")))
			.andExpect(jsonPath("$.periods[1].effectiveTo").doesNotExist());
	}

	@Test
	void hoursAtDifferentBranchesMustNotOverlap() throws Exception {
		UUID staffId = createStaff("Ayşe", branchA, branchB);
		String staff = salon + "/staff/" + staffId;

		api.put(owner.accessToken(), staff + "/branches/" + branchA + "/working-hours",
				hours("MONDAY", "09:00", "13:00"))
			.andExpect(status().isOk());
		api.put(owner.accessToken(), staff + "/branches/" + branchB + "/working-hours",
				hours("MONDAY", "12:00", "18:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WORKING_HOURS_CONFLICT"));
		api.put(owner.accessToken(), staff + "/branches/" + branchB + "/working-hours",
				hours("MONDAY", "13:00", "18:00"))
			.andExpect(status().isOk());
		// Replacing the hours at A is checked against B only, not against A's old hours.
		api.put(owner.accessToken(), staff + "/branches/" + branchA + "/working-hours",
				hours("MONDAY", "08:00", "13:00"))
			.andExpect(status().isOk());

		api.get(owner.accessToken(), staff + "/working-hours").andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void hoursAtABranchTheStaffMemberLeftStillCountUntilCleared() throws Exception {
		UUID staffId = createStaff("Ayşe", branchA, branchB);
		String staff = salon + "/staff/" + staffId;
		api.put(owner.accessToken(), staff + "/branches/" + branchA + "/working-hours",
				hours("MONDAY", "09:00", "17:00"))
			.andExpect(status().isOk());
		assign(staffId, "STAFF", branchB);

		api.put(owner.accessToken(), staff + "/branches/" + branchB + "/working-hours",
				hours("MONDAY", "09:00", "17:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WORKING_HOURS_CONFLICT"));
		api.put(owner.accessToken(), staff + "/branches/" + branchA + "/working-hours", "{\"periods\": []}")
			.andExpect(status().isOk());
		api.put(owner.accessToken(), staff + "/branches/" + branchB + "/working-hours",
				hours("MONDAY", "09:00", "17:00"))
			.andExpect(status().isOk());
	}

	@Test
	void concurrentConflictingHoursAtTwoBranchesCannotBothBeSaved() throws Exception {
		UUID staffId = createStaff("Ayşe", branchA, branchB);
		String staff = salon + "/staff/" + staffId;

		List<Integer> statuses = Concurrently.run(
				() -> api.put(owner.accessToken(), staff + "/branches/" + branchA + "/working-hours",
						hours("MONDAY", "09:00", "17:00")).andReturn().getResponse().getStatus(),
				() -> api.put(owner.accessToken(), staff + "/branches/" + branchB + "/working-hours",
						hours("MONDAY", "10:00", "18:00")).andReturn().getResponse().getStatus());

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT branch_id) FROM staff_working_period WHERE staff_id = ?",
				Integer.class, staffId)).isEqualTo(1);
	}

	@Test
	void stylistsManageOnlyTheirOwnHoursInTheirBranches() throws Exception {
		TestUser stylist = api.signUp("Stylist");
		UUID stylistId = api.addMember(owner, salonId, "STAFF", stylist);
		assign(stylistId, "STAFF", branchA);
		UUID colleagueId = createStaff("Colleague", branchA);

		api.put(stylist.accessToken(), salon + "/staff/" + stylistId + "/branches/" + branchA + "/working-hours",
				hours("FRIDAY", "10:00", "19:00"))
			.andExpect(status().isOk());
		api.put(stylist.accessToken(), salon + "/staff/" + stylistId + "/branches/" + branchB + "/working-hours",
				hours("FRIDAY", "10:00", "19:00"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_ASSIGNED"));
		api.put(stylist.accessToken(), salon + "/staff/" + colleagueId + "/branches/" + branchA + "/working-hours",
				hours("FRIDAY", "10:00", "19:00"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_PERMISSION"));
	}

	@Test
	void branchScopedMembersSeeWorkingHoursOnlyInTheirBranches() throws Exception {
		TestUser receptionist = api.signUp("Receptionist");
		assign(api.addMember(owner, salonId, "RECEPTIONIST", receptionist), "RECEPTIONIST", branchA);
		UUID staffId = createStaff("Ayşe", branchA, branchB);
		String staff = salon + "/staff/" + staffId;
		api.put(owner.accessToken(), staff + "/branches/" + branchA + "/working-hours", hours("MONDAY", "09:00", "12:00"));
		api.put(owner.accessToken(), staff + "/branches/" + branchB + "/working-hours", hours("MONDAY", "13:00", "18:00"));

		api.get(receptionist.accessToken(), staff + "/working-hours")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].branchId").value(contains(branchA.toString())));
		api.get(owner.accessToken(), staff + "/working-hours").andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void staffManageTheirOwnTimeOffWhichOthersWithoutPermissionCannotSee() throws Exception {
		TestUser stylist = api.signUp("Stylist");
		UUID stylistId = api.addMember(owner, salonId, "STAFF", stylist);
		assign(stylistId, "STAFF", branchA);
		TestUser receptionist = api.signUp("Receptionist");
		assign(api.addMember(owner, salonId, "RECEPTIONIST", receptionist), "RECEPTIONIST", branchA);
		String timeOff = salon + "/staff/" + stylistId + "/time-off";
		String start = TODAY.plusDays(7) + "T09:00:00+03:00";
		String end = TODAY.plusDays(7) + "T13:00:00+03:00";

		String timeOffId = read(api.post(stylist.accessToken(), timeOff, """
				{"startAt": "%s", "endAt": "%s", "reason": "Doktor"}
				""".formatted(start, end)).andExpect(status().isCreated()), "$.id");
		api.get(stylist.accessToken(), timeOff)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id").value(contains(timeOffId)));
		api.get(receptionist.accessToken(), timeOff)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_PERMISSION"));
		api.post(stylist.accessToken(), salon + "/staff/" + createStaff("Colleague", branchA) + "/time-off", """
				{"startAt": "%s", "endAt": "%s"}
				""".formatted(start, end)).andExpect(status().isForbidden());

		api.delete(owner.accessToken(), timeOff + "/" + timeOffId).andExpect(status().isNoContent());
		api.delete(owner.accessToken(), timeOff + "/" + timeOffId)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TIME_OFF_NOT_FOUND"));
	}

	@Test
	void timeOffMustEndAfterItStarts() throws Exception {
		UUID staffId = createStaff("Ayşe", branchA);

		api.post(owner.accessToken(), salon + "/staff/" + staffId + "/time-off", """
				{"startAt": "2026-12-01T13:00:00Z", "endAt": "2026-12-01T09:00:00Z"}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
		api.post(owner.accessToken(), salon + "/staff/" + UUID.randomUUID() + "/time-off", """
				{"startAt": "2026-12-01T09:00:00Z", "endAt": "2026-12-01T13:00:00Z"}
				""").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
	}

	@Test
	void suspendedSalonsCannotChangeTheirCatalogOrSchedules() throws Exception {
		UUID staffId = createStaff("Ayşe", branchA);
		UUID serviceId = api.createService(owner, salonId, "Cut", 30);
		api.approveSalon(owner, salonId);
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/suspend", "{\"note\": \"Check\"}")
			.andExpect(status().isOk());

		api.post(owner.accessToken(), salon + "/services", TestApi.serviceJson("Color", 60))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
		api.put(owner.accessToken(), salon + "/services/" + serviceId + "/branches/" + branchA, "{}")
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
		api.put(owner.accessToken(), salon + "/branches/" + branchA + "/opening-hours", "{\"intervals\": []}")
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
		api.put(owner.accessToken(), salon + "/staff/" + staffId + "/branches/" + branchA + "/working-hours",
				hours("MONDAY", "09:00", "17:00"))
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
		api.post(owner.accessToken(), salon + "/staff/" + staffId + "/time-off", """
				{"startAt": "2030-01-07T09:00:00Z", "endAt": "2030-01-07T12:00:00Z"}
				""").andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
		// Reading still works.
		api.get(owner.accessToken(), salon + "/services").andExpect(status().isOk());
	}

	private UUID createStaff(String name, UUID... branchIds) throws Exception {
		return UUID.fromString(read(api.post(owner.accessToken(), salon + "/staff", """
				{"displayName": "%s", "role": "STAFF", "bookable": true, "branchIds": [%s]}
				""".formatted(name, quoted(branchIds))).andExpect(status().isCreated()), "$.id"));
	}

	private void assign(UUID staffId, String role, UUID... branchIds) throws Exception {
		api.put(owner.accessToken(), salon + "/staff/" + staffId, """
				{"displayName": "Member", "role": "%s", "bookable": true, "branchIds": [%s]}
				""".formatted(role, quoted(branchIds))).andExpect(status().isOk());
	}

	private static String quoted(UUID... ids) {
		return String.join(", ", Arrays.stream(ids).map(id -> "\"" + id + "\"").toList());
	}

	/** One open-ended period from 2026-01-01 with a single interval. */
	static String hours(String day, String start, String end) {
		return """
				{"periods": [{"effectiveFrom": "2026-01-01",
				              "intervals": [{"dayOfWeek": "%s", "startTime": "%s", "endTime": "%s"}]}]}
				""".formatted(day, start, end);
	}

}
