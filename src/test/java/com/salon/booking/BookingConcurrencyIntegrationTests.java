package com.salon.booking;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.jayway.jsonpath.JsonPath;
import com.salon.BookableSalon;
import com.salon.Concurrently;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static com.salon.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Races the database must win (ADR-0005, ADR-0012 decisions 21, 23, 24, ADR-0013 decisions 28, 41).
 * Requests run on separate threads released at once; assertions check the database afterwards.
 */
@IntegrationTest
class BookingConcurrencyIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	private BookableSalon salon;

	private UUID cut;

	private UUID ayse;

	@BeforeEach
	void createSalon() throws Exception {
		salon = BookableSalon.create(api, "Race Salon");
		cut = salon.service("Cut", 60);
		ayse = salon.staff("Ayşe", "10:00", "14:00", cut);
	}

	/** ADR-0005 acceptance test: N concurrent attempts for one slot, exactly one success. */
	@Test
	void concurrentBookingsOfOneSlotHaveExactlyOneWinner() throws Exception {
		List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			TestUser customer = api.signUpCustomer("Racer " + i);
			attempts.add(() -> salon.book(customer, ayse, at(DAY, "10:00"), cut).andReturn().getResponse());
		}

		List<MockHttpServletResponse> responses = Concurrently.run(attempts);

		assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(1);
		assertThat(responses).filteredOn(r -> r.getStatus() == 409)
			.hasSize(7)
			.allSatisfy(r -> assertThat(r.getContentAsString()).contains("SLOT_UNAVAILABLE"));
		assertThat(blockingAppointments()).isEqualTo(1);
	}

	@Test
	void concurrentAnyStaffBookingsAreSpreadOverTheFreeStaff() throws Exception {
		salon.staff("Burak", "10:00", "14:00", cut);
		salon.staff("Cem", "10:00", "14:00", cut);
		List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			TestUser customer = api.signUpCustomer("Any " + i);
			attempts.add(() -> salon.book(customer, null, at(DAY, "10:00"), cut).andReturn().getResponse());
		}

		List<MockHttpServletResponse> responses = Concurrently.run(attempts);

		assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT staff_id) FROM appointment WHERE salon_id = ?",
				Long.class, salon.salonId)).isEqualTo(3);
	}

	@Test
	void oneCustomerCannotBookOverlappingAppointmentsConcurrentlyAcrossSalons() throws Exception {
		BookableSalon other = BookableSalon.create(api, "Other Race Salon");
		UUID otherCut = other.service("Cut", 60);
		UUID deniz = other.staff("Deniz", "10:00", "14:00", otherCut);
		TestUser customer = api.signUpCustomer("Double");

		List<MockHttpServletResponse> responses = Concurrently.run(
				() -> salon.book(customer, ayse, at(DAY, "10:00"), cut).andReturn().getResponse(),
				() -> other.book(customer, deniz, at(DAY, "10:30"), otherCut).andReturn().getResponse());

		assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(1);
		assertThat(responses).filteredOn(r -> r.getStatus() == 409)
			.singleElement()
			.satisfies(r -> assertThat(r.getContentAsString()).contains("CUSTOMER_OVERLAP"));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM appointment WHERE customer_user_id = ? AND status IN ('PENDING', 'CONFIRMED')",
				Long.class, customer.id())).isEqualTo(1);
	}

	@Test
	void theCustomerLimitHoldsUnderConcurrency() throws Exception {
		TestUser customer = api.signUpCustomer("Limit");
		for (int day = 0; day < 4; day++) {
			salon.booked(customer, ayse, at(DAY.plusDays(day), "10:00"), cut);
		}
		List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
		for (int day = 4; day < 7; day++) {
			int offset = day;
			attempts.add(() -> salon.book(customer, ayse, at(DAY.plusDays(offset), "10:00"), cut)
				.andReturn()
				.getResponse());
		}

		List<MockHttpServletResponse> responses = Concurrently.run(attempts);

		assertThat(responses).filteredOn(r -> r.getStatus() == 201).hasSize(1);
		assertThat(responses).filteredOn(r -> r.getStatus() == 409)
			.hasSize(2)
			.allSatisfy(r -> assertThat(r.getContentAsString()).contains("CUSTOMER_APPOINTMENT_LIMIT"));
	}

	/** Decision 21: a booking and a conflicting time off never both succeed. */
	@Test
	void aBookingAndAConflictingScheduleChangeNeverBothSucceed() throws Exception {
		TestUser customer = api.signUpCustomer("Versus Time Off");

		List<MockHttpServletResponse> responses = Concurrently.run(
				() -> salon.book(customer, ayse, at(DAY, "10:00"), cut).andReturn().getResponse(),
				() -> api.post(salon.owner.accessToken(), salon.path + "/staff/" + ayse + "/time-off", """
						{"startAt": "%s", "endAt": "%s"}
						""".formatted(at(DAY, "09:00"), at(DAY, "12:00"))).andReturn().getResponse());

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
		long appointments = blockingAppointments();
		long timeOff = jdbc.queryForObject("SELECT count(*) FROM staff_time_off WHERE staff_id = ?", Long.class, ayse);
		assertThat(appointments + timeOff).isEqualTo(1);
	}

	@Test
	void bookingWithoutAnIdempotencyKeyIsRejected() throws Exception {
		TestUser customer = api.signUpCustomer("No Key");

		api.post(customer.accessToken(), "/api/v1/me/appointments",
				BookableSalon.bookJson(salon.branchId, ayse, at(DAY, "10:00"), cut))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void aRetryWithTheSameKeyReturnsTheSameAppointment() throws Exception {
		TestUser customer = api.signUpCustomer("Retry");
		String key = UUID.randomUUID().toString();
		String body = BookableSalon.bookJson(salon.branchId, ayse, at(DAY, "10:00"), cut);

		String first = read(bookWithKey(customer, body, key).andExpect(status().isCreated()), "$.id");
		bookWithKey(customer, body, key).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first));
		bookWithKey(customer, BookableSalon.bookJson(salon.branchId, ayse, at(DAY, "12:00"), cut), key)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertThat(blockingAppointments()).isEqualTo(1);
	}

	@Test
	void keysAreScopedPerUser() throws Exception {
		String key = UUID.randomUUID().toString();

		bookWithKey(api.signUpCustomer("Key A"), BookableSalon.bookJson(salon.branchId, ayse, at(DAY, "10:00"), cut),
				key).andExpect(status().isCreated());
		bookWithKey(api.signUpCustomer("Key B"), BookableSalon.bookJson(salon.branchId, ayse, at(DAY, "12:00"), cut),
				key).andExpect(status().isCreated());
	}

	@Test
	void concurrentRetriesWithOneKeyCreateOneAppointment() throws Exception {
		TestUser customer = api.signUpCustomer("Concurrent Retry");
		String key = UUID.randomUUID().toString();
		String body = BookableSalon.bookJson(salon.branchId, null, at(DAY, "10:00"), cut);
		List<Callable<MockHttpServletResponse>> retries = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			retries.add(() -> bookWithKey(customer, body, key).andReturn().getResponse());
		}

		List<MockHttpServletResponse> responses = Concurrently.run(retries);

		assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
		assertThat(responses).extracting(r -> idOf(r.getContentAsString()))
			.containsOnly(idOf(responses.getFirst().getContentAsString()));
		assertThat(blockingAppointments()).isEqualTo(1);
	}

	private ResultActions bookWithKey(TestUser customer, String body, String key)
			throws Exception {
		return api.performWithKey(HttpMethod.POST, customer.accessToken(), "/api/v1/me/appointments", body, key);
	}

	private static String idOf(String json) {
		return JsonPath.read(json, "$.id");
	}

	private long blockingAppointments() {
		return jdbc.queryForObject(
				"SELECT count(*) FROM appointment WHERE salon_id = ? AND status IN ('PENDING', 'CONFIRMED')", Long.class,
				salon.salonId);
	}

}
