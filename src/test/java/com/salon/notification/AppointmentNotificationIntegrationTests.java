package com.salon.notification;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import com.salon.booking.AppointmentEvent;
import com.salon.notification.application.NotificationTexts;
import com.salon.notification.domain.Notification;
import com.salon.notification.domain.NotificationKind;
import com.salon.notification.domain.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static com.salon.notification.domain.NotificationKind.BOOKED_BY_SALON;
import static com.salon.notification.domain.NotificationKind.BOOKING_CONFIRMED;
import static com.salon.notification.domain.NotificationKind.BOOKING_REQUESTED;
import static com.salon.notification.domain.NotificationKind.CANCELLED_BY_SALON;
import static com.salon.notification.domain.NotificationKind.CANCELLED_BY_YOU;
import static com.salon.notification.domain.NotificationKind.DESK_CONFIRMATION_NEEDED;
import static com.salon.notification.domain.NotificationKind.RESCHEDULED_BY_SALON;
import static com.salon.notification.domain.NotificationKind.STAFF_APPOINTMENT_CANCELLED;
import static com.salon.notification.domain.NotificationKind.STAFF_APPOINTMENT_REASSIGNED;
import static com.salon.notification.domain.NotificationKind.STAFF_APPOINTMENT_RESCHEDULED;
import static com.salon.notification.domain.NotificationKind.STAFF_NEW_APPOINTMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In-app notifications for appointment changes (ADR-0014 decisions 44, 45, 56): recipients, the
 * state at the time of the change, idempotency and the outbox's atomicity.
 */
@IntegrationTest
@RecordApplicationEvents
class AppointmentNotificationIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	NotificationRepository notifications;

	@Autowired
	NotificationTexts texts;

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	ApplicationEvents events;

	private BookableSalon salon;

	private UUID cut;

	private TestUser ayseUser;

	private UUID ayse;

	private TestUser mehmetUser;

	private UUID mehmet;

	private TestUser desk;

	private TestUser otherBranchDesk;

	private TestUser customer;

	@BeforeEach
	void createSalon() throws Exception {
		salon = BookableSalon.create(api, "Bildirim Salonu");
		salon.bookingPolicy(false, 60, 120, 120);
		cut = salon.service("Cut", 60);
		ayseUser = api.signUp("Ayşe");
		ayse = stylist(ayseUser);
		mehmetUser = api.signUp("Mehmet");
		mehmet = stylist(mehmetUser);
		desk = api.signUp("Desk");
		salon.member("RECEPTIONIST", desk, salon.branchId);
		otherBranchDesk = api.signUp("Other Desk");
		salon.member("RECEPTIONIST", otherBranchDesk, salon.addBranch());
		customer = api.signUpCustomer("Notified Customer");
	}

	@Test
	void aBookingRequestGoesToTheCustomerAndTheBranchDesk() throws Exception {
		salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		awaitListeners();

		assertThat(kinds(customer)).containsExactly(BOOKING_REQUESTED);
		assertThat(kinds(salon.owner)).containsExactly(DESK_CONFIRMATION_NEEDED);
		assertThat(kinds(desk)).containsExactly(DESK_CONFIRMATION_NEEDED);
		// Outside the receptionist's branches; the stylist waits for the confirmation (decision 44).
		assertThat(kinds(otherBranchDesk)).isEmpty();
		assertThat(kinds(ayseUser)).isEmpty();
	}

	@Test
	void membersAreNotNotifiedOfTheirOwnChanges() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		api.post(desk.accessToken(), salon.path + "/appointments/" + id + "/confirm", null).andExpect(status().isOk());
		// Ayşe cancels her own appointment (APPOINTMENT_MANAGE_OWN).
		api.post(ayseUser.accessToken(), salon.path + "/appointments/" + id + "/cancel",
				"{\"reason\": \"Hastalık nedeniyle\"}")
			.andExpect(status().isOk());
		awaitListeners();

		assertThat(kinds(customer)).containsExactlyInAnyOrder(BOOKING_REQUESTED, BOOKING_CONFIRMED, CANCELLED_BY_SALON);
		assertThat(kinds(desk)).containsExactly(DESK_CONFIRMATION_NEEDED);
		// Told about the confirmed request (decision 66), not about her own cancellation.
		assertThat(kinds(ayseUser)).containsExactly(STAFF_NEW_APPOINTMENT);

		Notification cancelled = only(customer, CANCELLED_BY_SALON);
		assertThat(cancelled.getReason()).isEqualTo("Hastalık nedeniyle");
		assertThat(texts.render(cancelled, NotificationTexts.TURKISH).body()).contains("Bildirim Salonu", "10:00",
				"Gerekçe: Hastalık nedeniyle");
	}

	@Test
	void eachNotificationKeepsTheStateOfItsChange() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		api.post(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/confirm", null)
			.andExpect(status().isOk());
		reschedule(id, "{\"start\": \"" + at(DAY, "11:00") + "\"}");
		reschedule(id, "{\"start\": \"" + at(DAY, "12:00") + "\", \"staffId\": \"" + mehmet + "\"}");
		awaitListeners();

		Notification confirmed = only(customer, BOOKING_CONFIRMED);
		assertThat(confirmed.getStartAt()).isEqualTo(at(DAY, "10:00"));
		assertThat(texts.render(confirmed, NotificationTexts.TURKISH).body()).contains("10:00")
			.doesNotContain("12:00");

		List<Notification> moved = of(customer, RESCHEDULED_BY_SALON);
		assertThat(moved).extracting(Notification::getPreviousStartAt)
			.containsExactlyInAnyOrder(at(DAY, "10:00"), at(DAY, "11:00"));
		assertThat(moved).extracting(Notification::getStartAt)
			.containsExactlyInAnyOrder(at(DAY, "11:00"), at(DAY, "12:00"));

		// Ayşe hears about the confirmed booking, that it moved, then that it left her book; Mehmet gets it.
		assertThat(only(ayseUser, STAFF_NEW_APPOINTMENT).getStartAt()).isEqualTo(at(DAY, "10:00"));
		assertThat(only(ayseUser, STAFF_APPOINTMENT_RESCHEDULED).getStartAt()).isEqualTo(at(DAY, "11:00"));
		Notification reassigned = only(ayseUser, STAFF_APPOINTMENT_REASSIGNED);
		assertThat(reassigned.getPreviousStartAt()).isEqualTo(at(DAY, "11:00"));
		assertThat(texts.render(reassigned, NotificationTexts.TURKISH).body()).contains("11:00");
		assertThat(only(mehmetUser, STAFF_NEW_APPOINTMENT).getStartAt()).isEqualTo(at(DAY, "12:00"));
		assertThat(kinds(salon.owner)).containsExactly(DESK_CONFIRMATION_NEEDED);
	}

	@Test
	void customersGetAConfirmationOfTheirOwnCancellation() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		api.post(customer.accessToken(), "/api/v1/me/appointments/" + id + "/cancel", "{}").andExpect(status().isOk());
		awaitListeners();

		assertThat(kinds(customer)).containsExactlyInAnyOrder(BOOKING_REQUESTED, CANCELLED_BY_YOU);
		assertThat(kinds(ayseUser)).containsExactly(STAFF_APPOINTMENT_CANCELLED);
	}

	@Test
	void walkInClientsAndDeactivatedMembersGetNothing() throws Exception {
		api.post(salon.owner.accessToken(), salon.path + "/staff/" + staffIdOf(desk) + "/deactivate", null)
			.andExpect(status().isOk());
		UUID walkIn = salon.walkIn("Walk-in Client");
		salon.bookAtSalon(salon.owner, walkIn, ayse, at(DAY, "10:00"), cut).andExpect(status().isCreated());
		salon.booked(customer, mehmet, at(DAY, "11:00"), cut);
		awaitListeners();

		assertThat(kinds(ayseUser)).containsExactly(STAFF_NEW_APPOINTMENT);
		assertThat(kinds(salon.owner)).containsExactly(DESK_CONFIRMATION_NEEDED);
		assertThat(kinds(desk)).isEmpty();
		assertThat(notifications.findAll())
			.filteredOn(notification -> salon.salonId.equals(notification.getSalonId()))
			.extracting(Notification::getKind)
			.doesNotContain(BOOKED_BY_SALON);
	}

	@Test
	void aRedeliveredEventCreatesNothingTwice() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		awaitListeners();
		AppointmentEvent booked = events.stream(AppointmentEvent.Booked.class)
			.filter(event -> event.appointmentId().equals(id))
			.findFirst()
			.orElseThrow();
		int before = notifications.findBySourceId(booked.historyEntryId()).size();

		transactions.executeWithoutResult(status -> publisher.publishEvent(booked));
		awaitListeners();

		assertThat(before).isEqualTo(3);
		assertThat(notifications.findBySourceId(booked.historyEntryId())).hasSize(before);
	}

	@Test
	void aRolledBackChangeLeavesNothingToDeliver() throws Exception {
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		awaitListeners();
		AppointmentEvent booked = events.stream(AppointmentEvent.Booked.class)
			.filter(event -> event.appointmentId().equals(id))
			.findFirst()
			.orElseThrow();
		long publications = publicationsOf(booked.historyEntryId());

		transactions.executeWithoutResult(status -> {
			publisher.publishEvent(booked);
			status.setRollbackOnly();
		});
		awaitListeners();

		assertThat(publicationsOf(booked.historyEntryId())).isEqualTo(publications);
		assertThat(notifications.findBySourceId(booked.historyEntryId())).hasSize(3);
	}

	private UUID stylist(TestUser user) throws Exception {
		UUID staffId = salon.member("STAFF", user, salon.branchId);
		salon.skills(staffId, cut);
		salon.workingHours(staffId, salon.branchId, "10:00", "14:00");
		return staffId;
	}

	private UUID staffIdOf(TestUser user) {
		return jdbc.queryForObject("SELECT id FROM staff_member WHERE salon_id = ? AND user_id = ?", UUID.class,
				salon.salonId, user.id());
	}

	private void reschedule(UUID id, String json) throws Exception {
		api.postWithKey(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/reschedule", json)
			.andExpect(status().isOk());
	}

	/** Waits until every event publication has been handled by its listeners (they run after commit). */
	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
					Long.class) == 0);
	}

	private long publicationsOf(UUID historyEntryId) {
		return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?", Long.class,
				"%" + historyEntryId + "%");
	}

	/** The user's notifications (listeners run asynchronously, so their order is not asserted). */
	private List<NotificationKind> kinds(TestUser user) {
		return notifications.findByUserIdOrderByCreatedAtDesc(user.id())
			.reversed()
			.stream()
			.map(Notification::getKind)
			.toList();
	}

	private List<Notification> of(TestUser user, NotificationKind kind) {
		return notifications.findByUserIdOrderByCreatedAtDesc(user.id())
			.stream()
			.filter(notification -> notification.getKind() == kind)
			.toList();
	}

	private Notification only(TestUser user, NotificationKind kind) {
		List<Notification> found = of(user, kind);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

}
