package com.salon.scheduling;

import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constraints of the scheduling schema, checked directly against PostgreSQL with application
 * checks bypassed (ADR-0002, ADR-0006, ADR-0012). Each assertion names the constraint that must
 * fire.
 */
@IntegrationTest
class SchedulingDatabaseConstraintsIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transactionTemplate;

	private UUID userId;

	private UUID salonA;

	private UUID branchA;

	private UUID staffA;

	private UUID branchB;

	@BeforeEach
	void createSalons() throws Exception {
		userId = api.signUp("Scheduling Constraint User").id();
		salonA = insertSalon();
		branchA = insertBranch(salonA);
		staffA = insertStaff(salonA);
		branchB = insertBranch(insertSalon());
	}

	@Test
	void workingPeriodsStayWithinOneSalon() {
		assertViolates("staff_working_period_branch_fk", () -> insertPeriod(branchB, "2026-01-01", null));
		assertViolates("staff_working_period_order_ck", () -> insertPeriod(branchA, "2026-02-01", "2026-01-31"));
	}

	@Test
	void intervalsAreValidWeekdaysAndDoNotRunOvernight() {
		UUID period = insertPeriod(branchA, "2026-01-01", null);

		assertViolates("staff_working_interval_day_ck", () -> insertInterval(period, 8, "09:00", "17:00"));
		assertViolates("staff_working_interval_order_ck", () -> insertInterval(period, 1, "22:00", "02:00"));
		assertViolates("branch_hours_interval_order_ck",
				() -> jdbc.update("INSERT INTO branch_hours_interval VALUES (?, 1, '18:00', '09:00')",
						insertBranchHours(branchA)));
	}

	@Test
	void deletingAPeriodDeletesItsIntervals() {
		UUID period = insertPeriod(branchA, "2026-01-01", null);
		insertInterval(period, 1, "09:00", "17:00");

		jdbc.update("DELETE FROM staff_working_period WHERE id = ?", period);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_working_interval WHERE period_id = ?",
				Integer.class, period)).isZero();
	}

	@Test
	void branchCalendarRowsAreUniquePerBranchAndOrdered() {
		insertBranchHours(branchA);
		assertViolates("branch_hours_branch_uk", () -> insertBranchHours(branchA));
		insertSpecialDay(branchA, "2026-12-31");
		assertViolates("branch_special_day_branch_date_uk", () -> insertSpecialDay(branchA, "2026-12-31"));
		assertViolates("branch_closure_order_ck", () -> jdbc.update("""
				INSERT INTO branch_closure (id, salon_id, branch_id, start_date, end_date, created_at, version)
				VALUES (?, ?, ?, '2026-12-02', '2026-12-01', now(), 0)
				""", UUID.randomUUID(), salonA, branchA));
	}

	@Test
	void timeOffEndsAfterItStarts() {
		assertViolates("staff_time_off_order_ck", () -> jdbc.update("""
				INSERT INTO staff_time_off (id, salon_id, staff_id, start_at, end_at, created_by_user_id, created_at,
				                            version)
				VALUES (?, ?, ?, '2026-12-01T12:00Z', '2026-12-01T12:00Z', ?, now(), 0)
				""", UUID.randomUUID(), salonA, staffA, userId));
	}

	private UUID insertPeriod(UUID branchId, String from, String to) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_working_period (id, salon_id, staff_id, branch_id, effective_from, effective_to,
				                                  created_at, version)
				VALUES (?, ?, ?, ?, ?::date, ?::date, now(), 0)
				""", id, salonA, staffA, branchId, from, to);
		return id;
	}

	private void insertInterval(UUID periodId, int day, String start, String end) {
		jdbc.update("INSERT INTO staff_working_interval VALUES (?, ?, ?::time, ?::time)", periodId, day, start, end);
	}

	private UUID insertBranchHours(UUID branchId) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO branch_hours (id, salon_id, branch_id, updated_at, version) VALUES (?, ?, ?, now(), 0)
				""", id, salonA, branchId);
		return id;
	}

	private void insertSpecialDay(UUID branchId, String date) {
		jdbc.update("""
				INSERT INTO branch_special_day (id, salon_id, branch_id, on_date, updated_at, version)
				VALUES (?, ?, ?, ?::date, now(), 0)
				""", UUID.randomUUID(), salonA, branchId, date);
	}

	private UUID insertSalon() {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO salon (id, name, slug, status, created_by_user_id, created_at, updated_at, version)
				VALUES (?, 'Salon', ?, 'DRAFT', ?, now(), now(), 0)
				""", id, "s-" + id, userId);
		return id;
	}

	private UUID insertBranch(UUID salonId) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO branch (id, salon_id, name, slug, province_id, district, address_line, time_zone, status,
				                    auto_confirm, slot_interval_minutes, min_notice_minutes, max_advance_days,
				                    cancellation_cutoff_minutes, pending_confirmation_timeout_minutes, created_at,
				                    updated_at, version)
				VALUES (?, ?, 'Branch', ?, 34, 'Kadıköy', 'Moda', 'Europe/Istanbul', 'ACTIVE', true, 15, 60, 60, 120,
				        120, now(), now(), 0)
				""", id, salonId, "b-" + id);
		return id;
	}

	private UUID insertStaff(UUID salonId) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_member (id, salon_id, display_name, role, status, bookable, created_at, updated_at,
				                          version)
				VALUES (?, ?, 'Staff', 'STAFF', 'ACTIVE', true, now(), now(), 0)
				""", id, salonId);
		return id;
	}

	/** Runs the statement in its own transaction and expects the named constraint to reject it. */
	private void assertViolates(String constraint, Runnable statement) {
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> statement.run()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

}
