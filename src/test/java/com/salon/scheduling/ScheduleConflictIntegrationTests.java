package com.salon.scheduling;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import static com.salon.TestApi.read;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Decision 21 (ADR-0012): schedule changes that take time away from future blocking appointments
 * are rejected with {@code 409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS}. Appointments come from
 * {@link TestBlockingAppointments} until booking exists.
 * <p>
 * Fixture: branch open 09:00–18:00 daily; Ayşe works 10:00–14:00 daily; one appointment five days
 * ahead, 11:00–12:00 plus a 15-minute buffer (blocked until 12:15).
 */
@IntegrationTest
class ScheduleConflictIntegrationTests {

	private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

	private static final LocalDate DAY = LocalDate.now(ISTANBUL).plusDays(5);

	private static final String CONFLICT = "SCHEDULE_CONFLICTS_WITH_APPOINTMENTS";

	@Autowired
	TestApi api;

	@Autowired
	TestBlockingAppointments appointments;

	private TestUser owner;

	private UUID salonId;

	private UUID branchId;

	private UUID ayse;

	private BlockingAppointment appointment;

	private String salon;

	private String branch;

	@BeforeEach
	void createScheduleWithAnAppointment() throws Exception {
		owner = api.signUp("Conflict Owner");
		salonId = api.createSalon(owner, "Conflict Salon");
		branchId = api.createBranch(owner, salonId);
		salon = "/api/v1/salons/" + salonId;
		branch = salon + "/branches/" + branchId;
		ayse = UUID.fromString(read(api.post(owner.accessToken(), salon + "/staff", """
				{"displayName": "Ayşe", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(branchId)).andExpect(status().isCreated()), "$.id"));
		api.put(owner.accessToken(), branch + "/opening-hours", "{\"intervals\": " + everyDay("09:00", "18:00") + "}")
			.andExpect(status().isOk());
		workingHours("10:00", "14:00").andExpect(status().isOk());
		appointment = appointments.add(salonId, branchId, ayse, at(DAY, "11:00"), at(DAY, "12:15"));
	}

	@AfterEach
	void removeAppointments() {
		appointments.clear(salonId);
	}

	@Test
	void openingHoursThatCloseDuringAnAppointmentAreRejected() throws Exception {
		api.put(owner.accessToken(), branch + "/opening-hours", "{\"intervals\": " + everyDay("09:00", "12:00") + "}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(CONFLICT))
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.appointments[0].id").value(appointment.id().toString()))
			.andExpect(jsonPath("$.appointments[0].staffId").value(ayse.toString()))
			.andExpect(jsonPath("$.appointments[0].end").exists());

		// Closing exactly when the buffer ends is fine.
		api.put(owner.accessToken(), branch + "/opening-hours", "{\"intervals\": " + everyDay("09:00", "12:15") + "}")
			.andExpect(status().isOk());
	}

	@Test
	void specialDaysAndClosuresOnTheAppointmentDateAreRejected() throws Exception {
		api.put(owner.accessToken(), branch + "/special-days/" + DAY, "{\"intervals\": []}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(CONFLICT));
		api.put(owner.accessToken(), branch + "/special-days/" + DAY,
				"{\"intervals\": [{\"startTime\": \"11:30\", \"endTime\": \"18:00\"}]}")
			.andExpect(status().isConflict());
		api.post(owner.accessToken(), branch + "/closures", """
				{"startDate": "%s", "endDate": "%s"}
				""".formatted(DAY.minusDays(1), DAY.plusDays(1))).andExpect(status().isConflict());

		// Other days are not affected.
		api.put(owner.accessToken(), branch + "/special-days/" + DAY.plusDays(1), "{\"intervals\": []}")
			.andExpect(status().isOk());
		api.post(owner.accessToken(), branch + "/closures", """
				{"startDate": "%s", "endDate": "%s"}
				""".formatted(DAY.plusDays(2), DAY.plusDays(3))).andExpect(status().isCreated());
	}

	@Test
	void removingASpecialDayThatKeptTheBranchOpenIsRejected() throws Exception {
		api.put(owner.accessToken(), branch + "/special-days/" + DAY,
				"{\"intervals\": [{\"startTime\": \"09:00\", \"endTime\": \"18:00\"}]}")
			.andExpect(status().isOk());
		// The special day covers the appointment, so shorter weekly hours are fine...
		api.put(owner.accessToken(), branch + "/opening-hours", "{\"intervals\": " + everyDay("09:00", "11:00") + "}")
			.andExpect(status().isOk());

		// ...until the special day goes.
		api.delete(owner.accessToken(), branch + "/special-days/" + DAY)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(CONFLICT));
	}

	@Test
	void workingHoursThatNoLongerCoverAnAppointmentAreRejected() throws Exception {
		workingHours("10:00", "12:00").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(CONFLICT));
		api.put(owner.accessToken(), salon + "/staff/" + ayse + "/branches/" + branchId + "/working-hours",
				"{\"periods\": []}")
			.andExpect(status().isConflict());

		workingHours("11:00", "12:15").andExpect(status().isOk());
	}

	@Test
	void timeOffOverlappingAnAppointmentOrItsBufferIsRejected() throws Exception {
		String timeOff = salon + "/staff/" + ayse + "/time-off";

		api.post(owner.accessToken(), timeOff, timeOffJson("12:00", "13:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(CONFLICT))
			.andExpect(jsonPath("$.appointments", hasSize(1)));
		api.post(owner.accessToken(), timeOff, timeOffJson("12:15", "13:00")).andExpect(status().isCreated());
	}

	@Test
	void staffSeeTheirOwnConflictingAppointments() throws Exception {
		TestUser stylist = api.signUp("Stylist");
		UUID stylistId = api.addMember(owner, salonId, "STAFF", stylist);
		api.put(owner.accessToken(), salon + "/staff/" + stylistId, """
				{"displayName": "Stylist", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(branchId)).andExpect(status().isOk());
		BlockingAppointment own = appointments.add(salonId, branchId, stylistId, at(DAY, "15:00"), at(DAY, "16:00"));

		api.post(stylist.accessToken(), salon + "/staff/" + stylistId + "/time-off", timeOffJson("14:00", "18:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.appointments[0].id").value(own.id().toString()));
	}

	@Test
	void appointmentsThatEndedOrAreElsewhereDoNotBlock() throws Exception {
		appointments.clear(salonId);
		LocalDate yesterday = LocalDate.now(ISTANBUL).minusDays(1);
		appointments.add(salonId, branchId, ayse, at(yesterday, "11:00"), at(yesterday, "12:00"));
		UUID otherBranch = api.createBranch(owner, salonId);
		appointments.add(salonId, otherBranch, UUID.randomUUID(), at(DAY, "11:00"), at(DAY, "12:00"));

		api.put(owner.accessToken(), branch + "/opening-hours", "{\"intervals\": []}").andExpect(status().isOk());
	}

	@Test
	void appointmentsAlreadyOutsideTheHoursAreNotBlamedOnAnUnrelatedChange() throws Exception {
		appointments.add(salonId, branchId, ayse, at(DAY, "16:00"), at(DAY, "17:00"));

		workingHours("10:00", "13:00").andExpect(status().isOk());
	}

	private ResultActions workingHours(String start, String end) throws Exception {
		return api.put(owner.accessToken(), salon + "/staff/" + ayse + "/branches/" + branchId + "/working-hours",
				"{\"periods\": [{\"effectiveFrom\": \"2026-01-01\", \"intervals\": " + everyDay(start, end) + "}]}");
	}

	private static String timeOffJson(String start, String end) {
		return "{\"startAt\": \"%s\", \"endAt\": \"%s\"}".formatted(at(DAY, start), at(DAY, end));
	}

	private static Instant at(LocalDate date, String time) {
		return ZonedDateTime.of(date, LocalTime.parse(time), ISTANBUL).toInstant();
	}

	private static String everyDay(String start, String end) {
		return "[" + String.join(",", Arrays.stream(DayOfWeek.values())
			.map(day -> "{\"dayOfWeek\": \"%s\", \"startTime\": \"%s\", \"endTime\": \"%s\"}".formatted(day, start, end))
			.toList()) + "]";
	}

}
