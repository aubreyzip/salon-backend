package com.salon.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.ZONE;
import static com.salon.BookableSalon.at;
import static com.salon.TestApi.read;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The salon's appointment book (ADR-0013 decisions 32-37): walk-ins, salon booking rules, the
 * lifecycle, and who may see and change what (ADR-0010). Fixture: a public salon open 09:00–18:00,
 * a 60-minute "Cut", Ayşe working 10:00–14:00.
 */
@IntegrationTest
class SalonBookingIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	private BookableSalon salon;

	private UUID cut;

	private UUID ayse;

	private UUID walkIn;

	@BeforeEach
	void createSalon() throws Exception {
		salon = BookableSalon.create(api, "Salon Book");
		cut = salon.service("Cut", 60);
		ayse = salon.staff("Ayşe", "10:00", "14:00", cut);
		walkIn = salon.walkIn("Walk-in Client");
	}

	@Test
	void salonBookingsAreConfirmedAndMayStartOffTheGrid() throws Exception {
		salon.bookAtSalon(salon.owner, walkIn, ayse, at(DAY, "10:07"), cut)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.source").value("SALON"))
			.andExpect(jsonPath("$.client.displayName").value("Walk-in Client"))
			.andExpect(jsonPath("$.client.linked").value(false))
			.andExpect(jsonPath("$.end").value(at(DAY, "11:07").toString()));
	}

	@Test
	void salonBookingsIgnoreNoticeButNotWorkingHours() throws Exception {
		salon.bookingPolicy(true, 10080, 120, 120);

		salon.bookAtSalon(salon.owner, walkIn, ayse, at(DAY, "10:00"), cut).andExpect(status().isCreated());
		salon.bookAtSalon(salon.owner, walkIn, ayse, at(DAY, "13:30"), cut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
	}

	@Test
	void walkInsMayStartUpToFifteenMinutesAgo() throws Exception {
		UUID always = salon.staff("Always", "00:00", "23:59", cut);
		salon.openingHours(salon.branchId, "00:00", "23:59");
		Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
		if (!LocalDate.ofInstant(now.minusSeconds(20 * 60), ZONE).equals(LocalDate.ofInstant(now.plusSeconds(3600), ZONE))) {
			return; // around midnight the hours above do not cover the test range
		}

		salon.bookAtSalon(salon.owner, walkIn, always, now.minusSeconds(14 * 60), cut).andExpect(status().isCreated());
		salon.bookAtSalon(salon.owner, salon.walkIn("Late"), always, now.minusSeconds(17 * 60), cut)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_START"));
	}

	@Test
	void salonsBookUpTo365DaysAheadWhateverTheCustomerLimit() throws Exception {
		LocalDate far = LocalDate.now(ZONE).plusDays(300);

		salon.bookAtSalon(salon.owner, walkIn, ayse, at(far, "10:00"), cut).andExpect(status().isCreated());
		salon.bookAtSalon(salon.owner, walkIn, ayse, at(LocalDate.now(ZONE).plusDays(366), "10:00"), cut)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_START"));
	}

	@Test
	void staffMayBookInInactiveBranches() throws Exception {
		UUID closed = salon.addBranch();
		salon.offer(cut, closed);
		api.put(salon.owner.accessToken(), salon.path + "/staff/" + ayse, """
				{"displayName": "Ayşe", "role": "STAFF", "bookable": true, "branchIds": ["%s", "%s"]}
				""".formatted(salon.branchId, closed)).andExpect(status().isOk());
		salon.workingHours(ayse, closed, "15:00", "17:00");
		api.post(salon.owner.accessToken(), salon.path + "/branches/" + closed + "/deactivate", null)
			.andExpect(status().isOk());

		api.postWithKey(salon.owner.accessToken(), salon.path + "/appointments", """
				{"branchId": "%s", "clientId": "%s", "serviceIds": ["%s"], "staffId": "%s", "start": "%s"}
				""".formatted(closed, walkIn, cut, ayse, at(DAY, "15:00"))).andExpect(status().isCreated());
	}

	@Test
	void anyStaffMemberWorksForSalonBookingsToo() throws Exception {
		salon.bookAtSalon(salon.owner, walkIn, null, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.staffId").value(ayse.toString()));
	}

	@Test
	void salonBookingsForALinkedClientCountTowardTheCustomerRules() throws Exception {
		TestUser customer = api.signUpCustomer("Linked");
		salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		String linkedClient = read(api.get(salon.owner.accessToken(), salon.path + "/appointments")
			.andExpect(status().isOk()), "$.content[0].client.id");
		UUID burak = salon.staff("Burak", "10:00", "14:00", cut);

		salon.bookAtSalon(salon.owner, UUID.fromString(linkedClient), burak, at(DAY, "10:30"), cut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CUSTOMER_OVERLAP"));
	}

	@Test
	void onlineCustomersBecomeLinkedClientsWithTheirCurrentNameAndPhone() throws Exception {
		TestUser customer = api.signUpCustomer("Zeynep");
		salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		salon.booked(customer, ayse, at(DAY.plusDays(1), "10:00"), cut);

		api.get(salon.owner.accessToken(), salon.path + "/clients")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[?(@.linked == true)]", hasSize(1)))
			.andExpect(jsonPath("$.content[?(@.linked == true)].displayName").value(contains("Zeynep")))
			.andExpect(jsonPath("$.content[?(@.linked == true)].phone").exists());
	}

	@Test
	void walkInPhonesAreNormalized() throws Exception {
		api.post(salon.owner.accessToken(), salon.path + "/clients", """
				{"displayName": "Phone Client", "phone": "0532 123 45 67"}
				""").andExpect(status().isCreated()).andExpect(jsonPath("$.phone").value("+905321234567"));
		api.post(salon.owner.accessToken(), salon.path + "/clients", """
				{"displayName": "Bad Phone", "phone": "12"}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"));
	}

	@Test
	void pendingBookingsAreConfirmedOrRejectedByTheSalon() throws Exception {
		salon.bookingPolicy(false, 60, 120, 120);
		TestUser customer = api.signUpCustomer("Pending");
		UUID first = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		UUID second = salon.booked(customer, ayse, at(DAY, "12:00"), cut);

		post(first, "confirm", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.confirmationDeadline").doesNotExist());
		post(first, "confirm", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
		post(second, "reject", "{\"reason\": \"Fully booked\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		api.get(customer.accessToken(), "/api/v1/me/appointments/" + second)
			.andExpect(jsonPath("$.status").value("REJECTED"))
			.andExpect(jsonPath("$.statusReason").value("Fully booked"));
	}

	@Test
	void salonCancellationsNeedAReasonTheCustomerSees() throws Exception {
		TestUser customer = api.signUpCustomer("Cancelled");
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		post(id, "cancel", "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		post(id, "cancel", "{\"reason\": \"Ayşe is ill\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.cancelledBy").value("SALON"));
		api.get(customer.accessToken(), "/api/v1/me/appointments/" + id)
			.andExpect(jsonPath("$.statusReason").value("Ayşe is ill"));
	}

	@Test
	void outcomesCanOnlyBeRecordedOnceTheAppointmentHasStarted() throws Exception {
		UUID id = UUID.fromString(read(salon.bookAtSalon(salon.owner, walkIn, ayse, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated()), "$.id"));

		post(id, "complete", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_STARTED"));
		jdbc.update("UPDATE appointment SET start_at = start_at - interval '5 days', end_at = end_at - interval "
				+ "'5 days', block_end_at = block_end_at - interval '5 days' WHERE id = ?", id);

		api.get(salon.owner.accessToken(), salon.path + "/appointments?awaitingOutcome=true")
			.andExpect(jsonPath("$.content[*].id").value(hasItem(id.toString())));
		post(id, "no-show", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_SHOW"));
		post(id, "complete", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
		api.get(salon.owner.accessToken(), salon.path + "/appointments?awaitingOutcome=true")
			.andExpect(jsonPath("$.content[*].id").value(not(hasItem(id.toString()))));
	}

	@Test
	void theHistoryRecordsEveryChangeWithItsActor() throws Exception {
		salon.bookingPolicy(false, 60, 120, 120);
		TestUser customer = api.signUpCustomer("History");
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		post(id, "confirm", null).andExpect(status().isOk());
		api.postWithKey(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/reschedule",
				"{\"start\": \"" + at(DAY, "11:00") + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));

		api.get(salon.owner.accessToken(), salon.path + "/appointments/" + id)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.history[*].action").value(contains("BOOKED", "CONFIRMED", "RESCHEDULED")))
			.andExpect(jsonPath("$.history[0].actorUserId").value(customer.id().toString()))
			.andExpect(jsonPath("$.history[1].actorUserId").value(salon.owner.id().toString()));
	}

	@Test
	void internalNotesAreNeverShownToTheCustomer() throws Exception {
		TestUser customer = api.signUpCustomer("Notes");
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		api.put(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/internal-note",
				"{\"note\": \"Allergic to dye\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.internalNote").value("Allergic to dye"));
		api.get(customer.accessToken(), "/api/v1/me/appointments/" + id)
			.andExpect(jsonPath("$.internalNote").doesNotExist());
	}

	@Test
	void stylistsSeeAndManageOnlyTheirOwnAppointmentsAndTheirClients() throws Exception {
		TestUser stylistUser = api.signUp("Stylist");
		UUID stylist = salon.member("STAFF", stylistUser, salon.branchId);
		salon.skills(stylist, cut);
		salon.workingHours(stylist, salon.branchId, "10:00", "14:00");
		UUID own = UUID.fromString(read(salon.bookAtSalon(stylistUser, walkIn, stylist, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated()), "$.id"));
		UUID colleagues = UUID.fromString(read(salon.bookAtSalon(salon.owner, walkIn, ayse, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated()), "$.id"));

		salon.bookAtSalon(stylistUser, walkIn, ayse, at(DAY, "12:00"), cut).andExpect(status().isForbidden());
		api.get(stylistUser.accessToken(), salon.path + "/appointments")
			.andExpect(jsonPath("$.content[*].id").value(contains(own.toString())))
			.andExpect(jsonPath("$.content[0].client.displayName").value("Walk-in Client"));
		api.get(stylistUser.accessToken(), salon.path + "/appointments/" + colleagues)
			.andExpect(status().isNotFound());
		api.post(stylistUser.accessToken(), salon.path + "/appointments/" + colleagues + "/cancel",
				"{\"reason\": \"Not mine\"}").andExpect(status().isNotFound());
		api.get(stylistUser.accessToken(), salon.path + "/clients").andExpect(status().isForbidden());
	}

	@Test
	void receptionistsWorkOnlyInTheirBranchesAndSeeOnlyThoseClients() throws Exception {
		UUID otherBranch = salon.addBranch();
		salon.offer(cut, otherBranch);
		UUID other = UUID.fromString(read(api.post(salon.owner.accessToken(), salon.path + "/staff", """
				{"displayName": "Elif", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(otherBranch)).andExpect(status().isCreated()), "$.id"));
		salon.skills(other, cut);
		salon.workingHours(other, otherBranch, "10:00", "14:00");
		UUID otherClient = salon.walkIn("Other Branch Client");
		api.postWithKey(salon.owner.accessToken(), salon.path + "/appointments", """
				{"branchId": "%s", "clientId": "%s", "serviceIds": ["%s"], "start": "%s"}
				""".formatted(otherBranch, otherClient, cut, at(DAY, "10:00"))).andExpect(status().isCreated());
		salon.bookAtSalon(salon.owner, walkIn, ayse, at(DAY, "10:00"), cut).andExpect(status().isCreated());
		TestUser receptionist = api.signUp("Receptionist");
		salon.member("RECEPTIONIST", receptionist, salon.branchId);

		api.get(receptionist.accessToken(), salon.path + "/clients")
			.andExpect(jsonPath("$.content[*].id").value(contains(walkIn.toString())));
		api.get(receptionist.accessToken(), salon.path + "/clients/" + otherClient).andExpect(status().isNotFound());
		api.get(receptionist.accessToken(), salon.path + "/appointments")
			.andExpect(jsonPath("$.content", hasSize(1)));
		api.postWithKey(receptionist.accessToken(), salon.path + "/appointments", """
				{"branchId": "%s", "clientId": "%s", "serviceIds": ["%s"], "start": "%s"}
				""".formatted(otherBranch, walkIn, cut, at(DAY, "12:00")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_ASSIGNED"));
	}

	@Test
	void clientsOfAnotherSalonCannotBeBooked() throws Exception {
		BookableSalon other = BookableSalon.create(api, "Other Clients");
		UUID foreignClient = other.walkIn("Foreign");

		salon.bookAtSalon(salon.owner, foreignClient, ayse, at(DAY, "10:00"), cut)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
	}

	private ResultActions post(UUID appointmentId, String action, String json)
			throws Exception {
		return api.post(salon.owner.accessToken(), salon.path + "/appointments/" + appointmentId + "/" + action, json);
	}

}
