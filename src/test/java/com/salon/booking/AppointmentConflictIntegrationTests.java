package com.salon.booking;

import java.time.DayOfWeek;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changes that affect real appointments (ADR-0012 decisions 21, 25, 26; ADR-0013 decision 38):
 * rejected with {@code 409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS}, or applied with an explicit
 * override that flags the appointments. Fixture: a public salon, a 60-minute "Cut", Ayşe working
 * 10:00–14:00 with a customer's appointment at 10:00.
 */
@IntegrationTest
class AppointmentConflictIntegrationTests {

	private static final String OVERRIDE = "\"conflictOverride\": {\"reason\": \"Ayşe is on sick leave\"}";

	@Autowired
	TestApi api;

	private BookableSalon salon;

	private UUID cut;

	private UUID ayse;

	private UUID appointment;

	private String owner;

	@BeforeEach
	void createSalonWithAnAppointment() throws Exception {
		salon = BookableSalon.create(api, "Conflict Salon");
		cut = salon.service("Cut", 60);
		ayse = salon.staff("Ayşe", "10:00", "14:00", cut);
		appointment = salon.booked(api.signUpCustomer("Booked Customer"), ayse, at(DAY, "10:00"), cut);
		owner = salon.owner.accessToken();
	}

	@Test
	void openingHoursThatNoLongerCoverAnAppointmentAreRejected() throws Exception {
		api.put(owner, salon.path + "/branches/" + salon.branchId + "/opening-hours", everyDay("12:00", "18:00", ""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICTS_WITH_APPOINTMENTS"))
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.appointments[0].id").value(appointment.toString()));
	}

	@Test
	void anOverrideAppliesTheChangeAndFlagsTheAppointment() throws Exception {
		api.put(owner, salon.path + "/branches/" + salon.branchId + "/opening-hours",
				everyDay("12:00", "18:00", ", " + OVERRIDE))
			.andExpect(status().isOk());

		api.get(owner, salon.path + "/appointments?needsAttention=true")
			.andExpect(jsonPath("$.content[*].id").value(contains(appointment.toString())))
			.andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
			.andExpect(jsonPath("$.content[0].attention.reason").value("Ayşe is on sick leave"));
		api.get(owner, salon.path + "/appointments/" + appointment)
			.andExpect(jsonPath("$.history[*].action").value(hasItem("ATTENTION_FLAGGED")));
	}

	@Test
	void overrideReasonsAreValidated() throws Exception {
		api.post(owner, salon.path + "/branches/" + salon.branchId + "/closures", """
				{"startDate": "%s", "endDate": "%s", "conflictOverride": {"reason": " "}}
				""".formatted(DAY, DAY)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void theOverrideNeedsItsOwnPermission() throws Exception {
		TestUser stylistUser = api.signUp("Own Stylist");
		UUID stylist = salon.member("STAFF", stylistUser, salon.branchId);
		salon.skills(stylist, cut);
		salon.workingHours(stylist, salon.branchId, "10:00", "14:00");
		salon.booked(api.signUpCustomer("Stylist Customer"), stylist, at(DAY, "11:00"), cut);
		String timeOff = salon.path + "/staff/" + stylist + "/time-off";
		String body = "{\"startAt\": \"%s\", \"endAt\": \"%s\"".formatted(at(DAY, "09:00"), at(DAY, "18:00"));

		api.post(stylistUser.accessToken(), timeOff, body + "}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICTS_WITH_APPOINTMENTS"));
		api.post(stylistUser.accessToken(), timeOff, body + ", " + OVERRIDE + "}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_PERMISSION"));
		api.post(owner, timeOff, body + ", " + OVERRIDE + "}").andExpect(status().isCreated());
	}

	@Test
	void deactivatingAStaffMemberWithAppointmentsIsRejectedUnlessOverridden() throws Exception {
		api.post(owner, salon.path + "/staff/" + ayse + "/deactivate", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICTS_WITH_APPOINTMENTS"));
		api.post(owner, salon.path + "/staff/" + ayse + "/deactivate", "{" + OVERRIDE + "}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));
		api.get(owner, salon.path + "/appointments?needsAttention=true")
			.andExpect(jsonPath("$.content[*].id").value(contains(appointment.toString())));
	}

	@Test
	void turningBookableOffOrUnassigningTheBranchConflicts() throws Exception {
		api.put(owner, salon.path + "/staff/" + ayse, """
				{"displayName": "Ayşe", "role": "STAFF", "bookable": false, "branchIds": ["%s"]}
				""".formatted(salon.branchId)).andExpect(status().isConflict());
		api.put(owner, salon.path + "/staff/" + ayse, """
				{"displayName": "Ayşe", "role": "STAFF", "bookable": true, "branchIds": []}
				""").andExpect(status().isConflict());
		api.put(owner, salon.path + "/staff/" + ayse, """
				{"displayName": "Ayşe Y.", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(salon.branchId)).andExpect(status().isOk());
	}

	@Test
	void removingASkillArchivingOrUnofferingABookedServiceConflicts() throws Exception {
		UUID color = salon.service("Color", 30);
		salon.skills(ayse, cut, color);

		api.put(owner, salon.path + "/staff/" + ayse + "/skills", "{\"skills\": [{\"serviceId\": \"" + color + "\"}]}")
			.andExpect(status().isConflict());
		api.post(owner, salon.path + "/services/" + cut + "/archive", null).andExpect(status().isConflict());
		api.delete(owner, salon.path + "/services/" + cut + "/branches/" + salon.branchId)
			.andExpect(status().isConflict());
		// Changes that do not touch the booked service are fine.
		api.post(owner, salon.path + "/services/" + color + "/archive", null).andExpect(status().isOk());
		api.perform(HttpMethod.POST, owner, salon.path + "/services/" + cut + "/archive", "{" + OVERRIDE + "}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.archived").value(true));
	}

	@Test
	void cancelledAppointmentsNeverBlock() throws Exception {
		api.post(owner, salon.path + "/appointments/" + appointment + "/cancel", "{\"reason\": \"Moved away\"}")
			.andExpect(status().isOk());

		api.post(owner, salon.path + "/staff/" + ayse + "/deactivate", null).andExpect(status().isOk());
	}

	@Test
	void reschedulingOrResolvingClearsTheFlag() throws Exception {
		UUID second = salon.booked(api.signUpCustomer("Second"), ayse, at(DAY, "12:00"), cut);
		api.post(owner, salon.path + "/staff/" + ayse + "/time-off", """
				{"startAt": "%s", "endAt": "%s", %s}
				""".formatted(at(DAY, "09:00"), at(DAY, "11:30"), OVERRIDE)).andExpect(status().isCreated());
		UUID burak = salon.staff("Burak", "10:00", "14:00", cut);

		api.postWithKey(owner, salon.path + "/appointments/" + appointment + "/reschedule",
				"{\"start\": \"" + at(DAY, "10:00") + "\", \"staffId\": \"" + burak + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.staffId").value(burak.toString()))
			.andExpect(jsonPath("$.attention").doesNotExist());
		// The second appointment (12:00) was not affected, so it is not flagged.
		api.post(owner, salon.path + "/appointments/" + second + "/resolve-attention", null)
			.andExpect(status().isConflict());
		api.get(owner, salon.path + "/appointments?needsAttention=true")
			.andExpect(jsonPath("$.content[*].id").value(not(hasItem(appointment.toString()))));
	}

	@Test
	void flaggedAppointmentsCanBeMarkedResolved() throws Exception {
		api.post(owner, salon.path + "/staff/" + ayse + "/time-off", """
				{"startAt": "%s", "endAt": "%s", %s}
				""".formatted(at(DAY, "09:00"), at(DAY, "12:00"), OVERRIDE)).andExpect(status().isCreated());

		api.post(owner, salon.path + "/appointments/" + appointment + "/resolve-attention", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attention").doesNotExist());
	}

	@Test
	void appointmentsAreAbsencesInAvailability() throws Exception {
		api.get(null, "/api/v1/public/branches/" + salon.branchId + "/availability?serviceIds=" + cut + "&from=" + DAY
				+ "&to=" + DAY)
			.andExpect(jsonPath("$.days[0].slots[*].localTime").value(not(hasItem("10:00:00"))))
			.andExpect(jsonPath("$.days[0].slots[*].localTime").value(hasItem("11:00:00")));
		api.get(owner, salon.path + "/branches/" + salon.branchId + "/availability?serviceIds=" + cut + "&from=" + DAY
				+ "&to=" + DAY)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days[0].slots[*].localTime").value(not(hasItem("10:30:00"))));
	}

	private static String everyDay(String start, String end, String extra) {
		StringBuilder intervals = new StringBuilder();
		for (DayOfWeek day : DayOfWeek.values()) {
			if (!intervals.isEmpty()) {
				intervals.append(',');
			}
			intervals.append("{\"dayOfWeek\": \"%s\", \"startTime\": \"%s\", \"endTime\": \"%s\"}".formatted(day,
					start, end));
		}
		return "{\"intervals\": [" + intervals + "]" + extra + "}";
	}

}
