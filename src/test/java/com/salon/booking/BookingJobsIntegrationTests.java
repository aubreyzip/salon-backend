package com.salon.booking;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import com.salon.booking.application.BookingJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Background jobs and domain events of the booking module (ADR-0009, ADR-0013 decisions 31, 39,
 * 41). The jobs are disabled in tests and run directly here.
 */
@IntegrationTest
@RecordApplicationEvents
class BookingJobsIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	BookingJobs jobs;

	@Autowired
	ApplicationEvents events;

	private BookableSalon salon;

	private UUID cut;

	private UUID ayse;

	private TestUser customer;

	@BeforeEach
	void createManuallyConfirmingSalon() throws Exception {
		salon = BookableSalon.create(api, "Jobs Salon");
		salon.bookingPolicy(false, 60, 120, 120);
		cut = salon.service("Cut", 60);
		ayse = salon.staff("Ayşe", "10:00", "14:00", cut);
		customer = api.signUpCustomer("Waiting Customer");
	}

	@Test
	void pendingAppointmentsExpireAfterTheirDeadlineAndFreeTheSlot() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		jdbc.update("UPDATE appointment SET confirmation_deadline = now() - interval '1 minute' WHERE id = ?", id);

		assertThat(jobs.expireDuePendingAppointments()).isGreaterThanOrEqualTo(1);

		api.get(customer.accessToken(), "/api/v1/me/appointments/" + id)
			.andExpect(jsonPath("$.status").value("EXPIRED"))
			.andExpect(jsonPath("$.confirmationDeadline").doesNotExist());
		api.get(salon.owner.accessToken(), salon.path + "/appointments/" + id)
			.andExpect(jsonPath("$.history[*].action").value(contains("BOOKED", "EXPIRED")))
			.andExpect(jsonPath("$.history[1].actorUserId").doesNotExist());
		salon.book(api.signUpCustomer("Next Customer"), ayse, at(DAY, "10:00"), cut).andExpect(status().isCreated());
		assertThat(events.stream(AppointmentEvent.Expired.class).map(AppointmentEvent::appointmentId)).contains(id);
	}

	@Test
	void appointmentsBeforeTheirDeadlineAreKept() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		jobs.expireDuePendingAppointments();

		api.get(customer.accessToken(), "/api/v1/me/appointments/" + id).andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void anExpiredDeadlineCannotBeConfirmed() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		jdbc.update("UPDATE appointment SET confirmation_deadline = now() - interval '1 minute' WHERE id = ?", id);

		api.post(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/confirm", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
	}

	@Test
	void everyChangePublishesAnEventWithIdsOnly() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		api.post(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/confirm", null)
			.andExpect(status().isOk());
		api.post(customer.accessToken(), "/api/v1/me/appointments/" + id + "/cancel", null).andExpect(status().isOk());

		assertThat(events.stream(AppointmentEvent.class).filter(event -> event.appointmentId().equals(id)))
			.extracting(event -> event.getClass().getSimpleName())
			.containsExactly("Booked", "Confirmed", "Cancelled");
		assertThat(events.stream(AppointmentEvent.Cancelled.class).filter(event -> event.appointmentId().equals(id)))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.byCustomer()).isTrue();
				assertThat(event.customerUserId()).isEqualTo(customer.id());
				assertThat(event.salonId()).isEqualTo(salon.salonId);
			});
	}

	@Test
	void idempotencyRecordsAreKeptForADay() throws Exception {
		salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		jdbc.update("UPDATE idempotency_record SET created_at = ? WHERE user_id = ?",
				Timestamp.from(Instant.now().minus(25, ChronoUnit.HOURS)), customer.id());
		salon.booked(customer, ayse, at(DAY, "12:00"), cut);

		jobs.deleteOldIdempotencyRecords();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE user_id = ?", Long.class,
				customer.id())).isEqualTo(1);
	}

}
