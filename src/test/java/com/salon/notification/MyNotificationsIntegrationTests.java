package com.salon.notification;

import java.time.Duration;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static com.salon.TestApi.read;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/v1/me/notifications} (ADR-0014 decision 45): the list, unread count and read state. */
@IntegrationTest
class MyNotificationsIntegrationTests {

	private static final String NOTIFICATIONS = "/api/v1/me/notifications";

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	private BookableSalon salon;

	private TestUser customer;

	private UUID appointmentId;

	@BeforeEach
	void requestAndConfirm() throws Exception {
		salon = BookableSalon.create(api, "Okunan Salon");
		salon.bookingPolicy(false, 60, 120, 120);
		UUID cut = salon.service("Cut", 60);
		UUID ayse = salon.staff("Ayşe", "10:00", "14:00", cut);
		customer = api.signUpCustomer("Reading Customer");
		appointmentId = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		// Listeners run asynchronously: wait, so the two notifications are created in this order.
		awaitListeners();
		api.post(salon.owner.accessToken(), salon.path + "/appointments/" + appointmentId + "/confirm", null)
			.andExpect(status().isOk());
		awaitListeners();
	}

	@Test
	void listsTheUsersNotificationsNewestFirstWithTheirTexts() throws Exception {
		api.get(customer.accessToken(), NOTIFICATIONS)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.page.totalElements").value(2))
			.andExpect(jsonPath("$.content[0].kind").value("BOOKING_CONFIRMED"))
			.andExpect(jsonPath("$.content[0].title").value("Randevunuz onaylandı"))
			.andExpect(jsonPath("$.content[0].body").value(containsString("Okunan Salon")))
			.andExpect(jsonPath("$.content[0].body").value(containsString("10:00")))
			.andExpect(jsonPath("$.content[0].appointmentId").value(appointmentId.toString()))
			.andExpect(jsonPath("$.content[0].salonId").value(salon.salonId.toString()))
			.andExpect(jsonPath("$.content[0].startAt").value(at(DAY, "10:00").toString()))
			.andExpect(jsonPath("$.content[0].readAt").value(nullValue()))
			.andExpect(jsonPath("$.content[1].kind").value("BOOKING_REQUESTED"));

		api.get(customer.accessToken(), NOTIFICATIONS + "?size=1&page=1")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].kind").value("BOOKING_REQUESTED"))
			.andExpect(jsonPath("$.page.totalPages").value(2));
	}

	@Test
	void markingAsReadIsIdempotentAndUpdatesTheUnreadCount() throws Exception {
		api.get(customer.accessToken(), NOTIFICATIONS + "/unread-count")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unread").value(2));
		String id = read(api.get(customer.accessToken(), NOTIFICATIONS), "$.content[0].id");

		String readAt = read(api.post(customer.accessToken(), NOTIFICATIONS + "/" + id + "/read", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readAt").value(notNullValue())), "$.readAt");
		api.post(customer.accessToken(), NOTIFICATIONS + "/" + id + "/read", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readAt").value(readAt));

		api.get(customer.accessToken(), NOTIFICATIONS + "/unread-count").andExpect(jsonPath("$.unread").value(1));
		api.get(customer.accessToken(), NOTIFICATIONS).andExpect(jsonPath("$.content[0].readAt").value(readAt));
	}

	@Test
	void otherUsersNotificationsCannotBeSeenOrRead() throws Exception {
		String id = read(api.get(customer.accessToken(), NOTIFICATIONS), "$.content[0].id");
		TestUser other = api.signUpCustomer("Other Customer");

		api.post(other.accessToken(), NOTIFICATIONS + "/" + id + "/read", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		api.post(other.accessToken(), NOTIFICATIONS + "/" + UUID.randomUUID() + "/read", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		api.get(other.accessToken(), NOTIFICATIONS)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].id").value(everyItem(not(id))));
		api.get(customer.accessToken(), NOTIFICATIONS + "/unread-count").andExpect(jsonPath("$.unread").value(2));
	}

	@Test
	void requiresAuthentication() throws Exception {
		api.get(null, NOTIFICATIONS).andExpect(status().isUnauthorized());
		api.get(null, NOTIFICATIONS + "/unread-count").andExpect(status().isUnauthorized());
		api.post(null, NOTIFICATIONS + "/" + UUID.randomUUID() + "/read", null).andExpect(status().isUnauthorized());
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
					Long.class) == 0);
	}

}
