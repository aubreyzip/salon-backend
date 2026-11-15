package com.salon.booking;

import java.util.List;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0014 decision 56: every event names the history entry of its change, and that entry still
 * describes the change after later changes overwrote the appointment row.
 */
@IntegrationTest
@RecordApplicationEvents
class AppointmentEventContractIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ApplicationEvents events;

	@Autowired
	AppointmentHistory history;

	private BookableSalon salon;

	private UUID cut;

	private UUID ayse;

	private UUID mehmet;

	@BeforeEach
	void createSalon() throws Exception {
		salon = BookableSalon.create(api, "Event Salon");
		salon.bookingPolicy(false, 60, 120, 120);
		cut = salon.service("Cut", 60);
		ayse = salon.staff("Ayşe", "10:00", "14:00", cut);
		mehmet = salon.staff("Mehmet", "10:00", "14:00", cut);
	}

	@Test
	void eachEventDescribesItsOwnChangeAfterLaterChanges() throws Exception {
		TestUser customer = api.signUpCustomer("Event Customer");
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);
		api.post(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/confirm", null)
			.andExpect(status().isOk());
		reschedule(id, "{\"start\": \"" + at(DAY, "11:00") + "\"}");
		reschedule(id, "{\"start\": \"" + at(DAY, "12:00") + "\", \"staffId\": \"" + mehmet + "\"}");

		List<AppointmentEvent> mine = events.stream(AppointmentEvent.class)
			.filter(event -> event.appointmentId().equals(id))
			.toList();
		assertThat(mine).hasSize(4);
		assertThat(mine).map(AppointmentEvent::historyEntryId).doesNotHaveDuplicates();

		AppointmentHistory.Change confirmed = change(mine.get(1));
		assertThat(mine.get(1)).isInstanceOf(AppointmentEvent.Confirmed.class);
		assertThat(confirmed.toStatus()).isEqualTo("CONFIRMED");
		assertThat(confirmed.startAt()).isEqualTo(at(DAY, "10:00"));
		assertThat(confirmed.endAt()).isEqualTo(at(DAY, "11:00"));
		assertThat(confirmed.previousStartAt()).isNull();

		AppointmentHistory.Change first = change(mine.get(2));
		assertThat(first.action()).isEqualTo("RESCHEDULED");
		assertThat(first.actorUserId()).isEqualTo(salon.owner.id());
		assertThat(first.previousStartAt()).isEqualTo(at(DAY, "10:00"));
		assertThat(first.startAt()).isEqualTo(at(DAY, "11:00"));
		assertThat(first.staffId()).isEqualTo(ayse);

		AppointmentHistory.Change second = change(mine.get(3));
		assertThat(second.previousStartAt()).isEqualTo(at(DAY, "11:00"));
		assertThat(second.previousEndAt()).isEqualTo(at(DAY, "12:00"));
		assertThat(second.previousStaffId()).isEqualTo(ayse);
		assertThat(second.startAt()).isEqualTo(at(DAY, "12:00"));
		assertThat(second.staffId()).isEqualTo(mehmet);
	}

	@Test
	void historyEntriesCannotBeUpdated() throws Exception {
		TestUser customer = api.signUpCustomer("Append Only");
		UUID id = salon.booked(customer, ayse, at(DAY, "10:00"), cut);

		assertThatThrownBy(() -> jdbc.update("UPDATE appointment_history SET reason = 'rewritten' "
				+ "WHERE appointment_id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
	}

	private AppointmentHistory.Change change(AppointmentEvent event) {
		AppointmentHistory.Change change = history.find(event.historyEntryId()).orElseThrow();
		assertThat(change.appointmentId()).isEqualTo(event.appointmentId());
		return change;
	}

	private void reschedule(UUID id, String json) throws Exception {
		api.postWithKey(salon.owner.accessToken(), salon.path + "/appointments/" + id + "/reschedule", json)
			.andExpect(status().isOk());
	}

}
