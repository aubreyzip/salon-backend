package com.salon.booking;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.salon.BookableSalon;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.salon.BookableSalon.DAY;
import static com.salon.BookableSalon.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constraints of the booking schema, checked directly against PostgreSQL with application checks
 * bypassed (ADR-0005, ADR-0012 decision 23). Each assertion names the constraint that must fire.
 * Includes the migration test ADR-0005 requires: the exclusion constraints over
 * {@code tstzrange(...)} are accepted and enforced.
 */
@IntegrationTest
class BookingDatabaseConstraintsIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	private BookableSalon salon;

	private UUID staffA;

	private UUID staffB;

	private UUID service;

	private UUID customer;

	private UUID client;

	@BeforeEach
	void createSalon() throws Exception {
		salon = BookableSalon.create(api, "Constraint Booking");
		service = salon.service("Cut", 60);
		staffA = salon.staff("A", "10:00", "14:00", service);
		staffB = salon.staff("B", "10:00", "14:00", service);
		customer = api.signUp("Constraint Customer").id();
		client = UUID.randomUUID();
		jdbc.update("INSERT INTO salon_client (id, salon_id, user_id, created_by_user_id, created_at, version) "
				+ "VALUES (?, ?, ?, ?, now(), 0)", client, salon.salonId, customer, customer);
	}

	@Test
	void theExclusionConstraintsAndTheirExtensionExist() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'", Long.class))
			.isEqualTo(1);
		assertThat(jdbc.queryForList("SELECT conname FROM pg_constraint WHERE contype = 'x' AND conrelid = "
				+ "'appointment'::regclass ORDER BY conname", String.class))
			.containsExactly("appointment_no_customer_overlap", "appointment_no_staff_overlap");
	}

	@Test
	void aStaffMemberCannotHaveOverlappingBlockingAppointmentsBufferIncluded() {
		insert(staffA, null, "10:00", "11:00", "11:30", "CONFIRMED");

		assertViolates("appointment_no_staff_overlap", () -> insert(staffA, null, "11:15", "12:15", "12:15", "PENDING"));
		assertThatCode(() -> insert(staffA, null, "11:30", "12:30", "12:30", "CONFIRMED")).doesNotThrowAnyException();
		assertThatCode(() -> insert(staffA, null, "10:00", "11:00", "11:00", "CANCELLED")).doesNotThrowAnyException();
		assertThatCode(() -> insert(staffB, null, "10:00", "11:00", "11:00", "CONFIRMED")).doesNotThrowAnyException();
	}

	@Test
	void aCustomerCannotHaveOverlappingBlockingAppointmentsBufferExcluded() {
		insert(staffA, customer, "10:00", "11:00", "11:30", "CONFIRMED");

		assertViolates("appointment_no_customer_overlap",
				() -> insert(staffB, customer, "10:30", "11:30", "11:30", "PENDING"));
		assertThatCode(() -> insert(staffB, customer, "11:00", "12:00", "12:00", "CONFIRMED"))
			.doesNotThrowAnyException();
		assertThatCode(() -> insert(staffB, null, "10:30", "11:00", "11:00", "CONFIRMED")).doesNotThrowAnyException();
	}

	@Test
	void appointmentRowsAreConsistent() {
		assertViolates("appointment_times_ck", () -> insert(staffA, null, "11:00", "10:00", "11:00", "CONFIRMED"));
		assertViolates("appointment_pending_deadline_ck", () -> jdbc.update(
				"UPDATE appointment SET status = 'PENDING', confirmation_deadline = NULL WHERE id = ?",
				insert(staffA, null, "12:00", "13:00", "13:00", "CONFIRMED")));
		assertViolates("appointment_cancelled_by_ck", () -> jdbc.update(
				"UPDATE appointment SET status = 'CANCELLED' WHERE id = ?",
				insert(staffA, null, "13:00", "13:30", "13:30", "CONFIRMED")));
		assertViolates("appointment_status_ck", () -> insert(staffA, null, "09:00", "09:30", "09:30", "BOOKED"));
	}

	@Test
	void appointmentsReferenceClientsOfTheirOwnSalon() throws Exception {
		BookableSalon other = BookableSalon.create(api, "Other Constraint Booking");
		UUID foreignClient = UUID.randomUUID();
		jdbc.update("INSERT INTO salon_client (id, salon_id, display_name, created_by_user_id, created_at, version) "
				+ "VALUES (?, ?, 'Foreign', ?, now(), 0)", foreignClient, other.salonId, customer);

		assertViolates("appointment_client_fk", () -> insertWithClient(foreignClient));
	}

	@Test
	void walkInClientsHaveANameAndLinkedClientsUseTheAccount() {
		assertViolates("salon_client_details_ck",
				() -> jdbc.update("INSERT INTO salon_client (id, salon_id, created_by_user_id, created_at, version) "
						+ "VALUES (?, ?, ?, now(), 0)", UUID.randomUUID(), salon.salonId, customer));
		assertViolates("salon_client_details_ck", () -> jdbc.update(
				"INSERT INTO salon_client (id, salon_id, user_id, display_name, created_by_user_id, created_at, version) "
						+ "VALUES (?, ?, ?, 'Copy', ?, now(), 0)",
				UUID.randomUUID(), salon.salonId, customer, customer));
		assertViolates("salon_client_salon_user_uk",
				() -> jdbc.update("INSERT INTO salon_client (id, salon_id, user_id, created_by_user_id, created_at, "
						+ "version) VALUES (?, ?, ?, ?, now(), 0)", UUID.randomUUID(), salon.salonId, customer, customer));
	}

	@Test
	void momentsAreStoredAsTheInstantsBooked() throws Exception {
		Instant start = at(DAY, "10:00");
		UUID id = salon.booked(api.signUpCustomer("Instant"), staffA, start, service);

		assertThat(jdbc.queryForObject("SELECT start_at = ?::timestamptz FROM appointment WHERE id = ?", Boolean.class,
				start.toString(), id)).isTrue();
	}

	private UUID insert(UUID staffId, UUID customerUserId, String start, String end, String blockEnd, String status) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO appointment (id, salon_id, branch_id, staff_id, client_id, customer_user_id, status, source,
				  start_at, end_at, block_end_at, buffer_minutes, confirmation_deadline, cancelled_by, created_by_user_id,
				  created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'SALON', ?, ?, ?, 0, ?, ?, ?, now(), now(), 0)
				""", id, salon.salonId, salon.branchId, staffId, client, customerUserId, status, ts(start), ts(end),
				ts(blockEnd), "PENDING".equals(status) ? ts(start) : null, "CANCELLED".equals(status) ? "SALON" : null,
				customer);
		return id;
	}

	private void insertWithClient(UUID clientId) {
		jdbc.update("""
				INSERT INTO appointment (id, salon_id, branch_id, staff_id, client_id, status, source, start_at, end_at,
				  block_end_at, buffer_minutes, created_by_user_id, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'CONFIRMED', 'SALON', ?, ?, ?, 0, ?, now(), now(), 0)
				""", UUID.randomUUID(), salon.salonId, salon.branchId, staffA, clientId, ts("15:00"), ts("16:00"),
				ts("16:00"), customer);
	}

	private static Timestamp ts(String localTime) {
		return Timestamp.from(at(DAY.plusDays(10), localTime));
	}

	private static void assertViolates(String constraint, Runnable statement) {
		assertThatThrownBy(statement::run).hasMessageContaining(constraint);
	}

}
