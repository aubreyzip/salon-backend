package com.salon.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Online booking by customers (ADR-0013 decisions 27-31, 40; ADR-0005; ADR-0007; ADR-0009). Fixture:
 * a public salon open 09:00–18:00, a 60-minute "Cut" offered online, Ayşe working 10:00–14:00.
 */
@IntegrationTest
class CustomerBookingIntegrationTests {

	@Autowired
	TestApi api;

	private BookableSalon salon;

	private UUID cut;

	private UUID ayse;

	private TestUser customer;

	@BeforeEach
	void createSalon() throws Exception {
		salon = BookableSalon.create(api, "Booking Salon");
		cut = salon.service("Cut", 60);
		ayse = salon.staff("Ayşe", "10:00", "14:00", cut);
		customer = api.signUpCustomer("Customer");
	}

	@Test
	void aCustomerBooksAConfirmedAppointmentWithPriceAndDurationAsBooked() throws Exception {
		salon.book(customer, ayse, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.staff.id").value(ayse.toString()))
			.andExpect(jsonPath("$.staff.displayName").value("Ayşe"))
			.andExpect(jsonPath("$.branch.timeZone").value("Europe/Istanbul"))
			.andExpect(jsonPath("$.start").value(at(DAY, "10:00").toString()))
			.andExpect(jsonPath("$.end").value(at(DAY, "11:00").toString()))
			.andExpect(jsonPath("$.items[0].name").value("Cut"))
			.andExpect(jsonPath("$.items[0].durationMinutes").value(60))
			.andExpect(jsonPath("$.totalPrice").value(450.0))
			.andExpect(jsonPath("$.confirmationDeadline").doesNotExist())
			.andExpect(jsonPath("$.changeableUntil").value(at(DAY, "08:00").toString()))
			.andExpect(jsonPath("$.internalNote").doesNotExist());

		api.get(customer.accessToken(), "/api/v1/me/appointments")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content", hasSize(1)))
			.andExpect(jsonPath("$.page.totalElements").value(1));
	}

	@Test
	void theBookedTimeIsNoLongerAvailableButBackToBackStartsAre() throws Exception {
		salon.booked(customer, ayse, at(DAY, "11:00"), cut);

		api.get(null, "/api/v1/public/branches/" + salon.branchId + "/availability?serviceIds=" + cut + "&from=" + DAY
				+ "&to=" + DAY)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.days[0].slots[*].localTime").value(contains("10:00:00", "12:00:00", "12:15:00",
					"12:30:00", "12:45:00", "13:00:00")));
		salon.book(api.signUpCustomer("Second"), ayse, at(DAY, "11:30"), cut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
		salon.book(api.signUpCustomer("Third"), ayse, at(DAY, "12:00"), cut).andExpect(status().isCreated());
	}

	@Test
	void theBufferKeepsTheStaffMemberBusyButNotTheCustomer() throws Exception {
		UUID color = salon.service("""
				{"categoryId": 1, "name": "Color", "durationMinutes": 60, "bufferMinutes": 30, "price": 900.00}
				""");
		salon.skills(ayse, cut, color);
		salon.booked(customer, ayse, at(DAY, "10:00"), color);

		salon.book(api.signUpCustomer("Next"), ayse, at(DAY, "11:00"), cut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
		salon.book(api.signUpCustomer("After Buffer"), ayse, at(DAY, "11:30"), cut).andExpect(status().isCreated());
	}

	@Test
	void customersNeedAVerifiedPhone() throws Exception {
		TestUser unverified = api.signUp("No Phone");

		salon.book(unverified, ayse, at(DAY, "10:00"), cut)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PHONE_NOT_VERIFIED"));
	}

	@Test
	void onlyPublicBranchesCanBeBookedOnline() throws Exception {
		UUID inactive = api.createBranch(salon.owner, salon.salonId);
		api.post(salon.owner.accessToken(), salon.path + "/branches/" + inactive + "/deactivate", null)
			.andExpect(status().isOk());

		api.postWithKey(customer.accessToken(), "/api/v1/me/appointments",
				BookableSalon.bookJson(inactive, null, at(DAY, "10:00"), cut))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
	}

	@Test
	void customerStartsFollowTheBranchGridNoticeAndAdvanceLimit() throws Exception {
		salon.book(customer, ayse, at(DAY, "10:05"), cut)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_START"));
		salon.book(customer, ayse, at(DAY.plusDays(61), "10:00"), cut)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_START"));
		salon.book(customer, ayse, Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MINUTES), cut)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_START"));
	}

	@Test
	void outsideWorkingHoursIsUnavailable() throws Exception {
		salon.book(customer, ayse, at(DAY, "13:15"), cut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
	}

	@Test
	void servicesNotBookableOnlineCanOnlyBeBookedByTheSalon() throws Exception {
		UUID consultation = salon.service("""
				{"categoryId": 1, "name": "Consultation", "durationMinutes": 30, "price": 0, "onlineBookable": false}
				""");
		salon.skills(ayse, cut, consultation);

		salon.book(customer, ayse, at(DAY, "10:00"), consultation)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SERVICE_NOT_OFFERED"));
		salon.bookAtSalon(salon.owner, salon.walkIn("Walk-in"), ayse, at(DAY, "10:00"), consultation)
			.andExpect(status().isCreated());
	}

	@Test
	void anyStaffMemberPicksTheLeastBookedQualifiedOneAndNeverAnUnqualifiedOne() throws Exception {
		UUID burak = salon.staff("Burak", "10:00", "14:00", cut);
		UUID unskilled = salon.staff("Cem", "10:00", "14:00");
		salon.booked(customer, ayse, at(DAY, "12:00"), cut);

		String picked = read(salon.book(api.signUpCustomer("Any"), null, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated()), "$.staff.id");
		assertThat(picked).isEqualTo(burak.toString()).isNotEqualTo(unskilled.toString());

		// Burak is now busy at 10:00; Ayşe is the only free one.
		salon.book(api.signUpCustomer("Any Again"), null, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.staff.id").value(ayse.toString()));
		salon.book(api.signUpCustomer("Nobody Left"), null, at(DAY, "10:00"), cut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
	}

	@Test
	void manualConfirmationBooksPendingUntilTheDeadline() throws Exception {
		salon.bookingPolicy(false, 60, 120, 120);

		String deadline = read(salon.book(customer, ayse, at(DAY, "10:00"), cut)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING")), "$.confirmationDeadline");
		Instant expected = Instant.now().plus(Duration.ofMinutes(120));
		assertThat(Instant.parse(deadline))
			.isBetween(expected.minusSeconds(60), expected.plusSeconds(5));
	}

	@Test
	void customersCancelBeforeTheCutoffAndTheSlotIsFreeAgain() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		api.post(customer.accessToken(), "/api/v1/me/appointments/" + id + "/cancel", "{\"reason\": \"Sick\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelledBy").value("CUSTOMER"))
			.andExpect(jsonPath("$.statusReason").value("Sick"));
		api.post(customer.accessToken(), "/api/v1/me/appointments/" + id + "/cancel", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
		salon.book(api.signUpCustomer("Takes It"), ayse, at(DAY, "10:00"), cut).andExpect(status().isCreated());
		api.get(customer.accessToken(), "/api/v1/me/appointments?scope=PAST")
			.andExpect(jsonPath("$.content[*].id").value(hasItem(id.toString())));
	}

	@Test
	void afterTheCutoffCustomersCannotCancelOrReschedule() throws Exception {
		salon.bookingPolicy(true, 0, 10080, 120);
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		api.post(customer.accessToken(), "/api/v1/me/appointments/" + id + "/cancel", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
		api.postWithKey(customer.accessToken(), "/api/v1/me/appointments/" + id + "/reschedule",
				"{\"start\": \"" + at(DAY, "12:00") + "\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
	}

	@Test
	void rescheduleMovesTheSameAppointmentAndReleasesTheOldTime() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		api.postWithKey(customer.accessToken(), "/api/v1/me/appointments/" + id + "/reschedule",
				"{\"start\": \"" + at(DAY, "10:30") + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.start").value(at(DAY, "10:30").toString()))
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
		salon.book(api.signUpCustomer("Earlier"), ayse, at(DAY, "12:00"), cut).andExpect(status().isCreated());
		salon.book(api.signUpCustomer("Overlaps"), ayse, at(DAY, "11:00"), cut)
			.andExpect(status().isConflict());
	}

	@Test
	void rescheduleWithManualConfirmationGoesBackToPending() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		salon.bookingPolicy(false, 60, 120, 120);

		api.postWithKey(customer.accessToken(), "/api/v1/me/appointments/" + id + "/reschedule",
				"{\"start\": \"" + at(DAY, "11:00") + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.confirmationDeadline").exists());
	}

	@Test
	void aCustomerCannotHoldOverlappingAppointmentsAcrossSalons() throws Exception {
		BookableSalon other = BookableSalon.create(api, "Other Salon");
		UUID otherCut = other.service("Cut", 60);
		UUID otherStaff = other.staff("Deniz", "10:00", "14:00", otherCut);
		salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		other.book(customer, otherStaff, at(DAY, "10:30"), otherCut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CUSTOMER_OVERLAP"));
		other.book(customer, otherStaff, at(DAY, "11:00"), otherCut).andExpect(status().isCreated());
	}

	@Test
	void atMostFiveActiveFutureAppointmentsPerCustomer() throws Exception {
		for (int day = 0; day < 5; day++) {
			salon.booked(customer, ayse, at(DAY.plusDays(day), "10:00"), cut);
		}

		salon.book(customer, ayse, at(DAY.plusDays(5), "10:00"), cut)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CUSTOMER_APPOINTMENT_LIMIT"))
			.andExpect(jsonPath("$.limit").value(5));
	}

	@Test
	void cancelledAppointmentsDoNotCountTowardTheLimit() throws Exception {
		UUID first = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		for (int day = 1; day < 5; day++) {
			salon.booked(customer, ayse, at(DAY.plusDays(day), "10:00"), cut);
		}
		api.post(customer.accessToken(), "/api/v1/me/appointments/" + first + "/cancel", null).andExpect(status().isOk());

		salon.book(customer, ayse, at(DAY.plusDays(5), "10:00"), cut).andExpect(status().isCreated());
	}

	@Test
	void customersSeeOnlyTheirOwnAppointments() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		TestUser other = api.signUpCustomer("Other");

		api.get(other.accessToken(), "/api/v1/me/appointments/" + id)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
		api.post(other.accessToken(), "/api/v1/me/appointments/" + id + "/cancel", null)
			.andExpect(status().isNotFound());
		api.get(other.accessToken(), "/api/v1/me/appointments")
			.andExpect(jsonPath("$.content[*].id").value(not(hasItem(id.toString()))));
	}

	@Test
	void duplicateServicesAreRejected() throws Exception {
		salon.book(customer, ayse, at(DAY, "10:00"), cut, cut)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DUPLICATE_SERVICE"));
	}

}
