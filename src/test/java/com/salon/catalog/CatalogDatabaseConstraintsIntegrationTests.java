package com.salon.catalog;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constraints of the catalog schema, checked directly against PostgreSQL with application checks
 * bypassed (ADR-0002, ADR-0012). Each assertion names the constraint that must fire.
 */
@IntegrationTest
class CatalogDatabaseConstraintsIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transactionTemplate;

	private UUID userId;

	private UUID salonA;

	private UUID salonB;

	@BeforeEach
	void createSalons() throws Exception {
		userId = api.signUp("Catalog Constraint User").id();
		salonA = insertSalon();
		salonB = insertSalon();
	}

	@Test
	void serviceValuesAreChecked() {
		assertViolates("service_item_currency_ck", () -> insertService(salonA, null, 30, "EUR"));
		assertViolates("service_item_duration_ck", () -> insertService(salonA, null, 0, "TRY"));
		assertViolates("service_item_status_ck",
				() -> jdbc.update("UPDATE service_item SET status = 'DELETED' WHERE id = ?",
						insertService(salonA, null, 30, "TRY")));
		assertViolates("service_item_category_fk", () -> jdbc.update("UPDATE service_item SET category_id = 1000 "
				+ "WHERE id = ?", insertService(salonA, null, 30, "TRY")));
	}

	@Test
	void servicesCannotUseSectionsOfAnotherSalon() {
		UUID sectionB = insertSection(salonB);

		assertThatCode(() -> insertService(salonB, sectionB, 30, "TRY")).doesNotThrowAnyException();
		assertViolates("service_item_section_fk", () -> insertService(salonA, sectionB, 30, "TRY"));
	}

	@Test
	void offeringsStayWithinOneSalonAndAreUniquePerBranch() {
		UUID serviceA = insertService(salonA, null, 30, "TRY");
		UUID branchA = insertBranch(salonA);
		UUID branchB = insertBranch(salonB);

		assertViolates("service_offering_branch_fk", () -> insertOffering(salonA, serviceA, branchB));
		assertViolates("service_offering_service_fk", () -> insertOffering(salonB, serviceA, branchB));
		insertOffering(salonA, serviceA, branchA);
		assertViolates("service_offering_service_branch_uk", () -> insertOffering(salonA, serviceA, branchA));
	}

	@Test
	void skillsStayWithinOneSalon() {
		UUID serviceA = insertService(salonA, null, 30, "TRY");
		UUID staffA = insertStaff(salonA);
		UUID staffB = insertStaff(salonB);

		assertViolates("staff_skill_staff_fk", () -> insertSkill(salonA, staffB, serviceA));
		assertViolates("staff_skill_service_fk", () -> insertSkill(salonB, staffB, serviceA));
		insertSkill(salonA, staffA, serviceA);
		assertViolates("staff_skill_staff_service_uk", () -> insertSkill(salonA, staffA, serviceA));
	}

	@Test
	void theCategoryTaxonomyIsSeeded() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM service_category", Integer.class)).isEqualTo(13);
		assertThat(jdbc.queryForObject("SELECT name FROM service_category WHERE code = 'HAIRCUT'", String.class))
			.isEqualTo("Saç Kesimi");
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

	private UUID insertSection(UUID salonId) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO service_section (id, salon_id, name, sort_order, created_at, updated_at, version)
				VALUES (?, ?, 'Saç', 0, now(), now(), 0)
				""", id, salonId);
		return id;
	}

	private UUID insertService(UUID salonId, UUID sectionId, int durationMinutes, String currency) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO service_item (id, salon_id, category_id, section_id, name, duration_minutes,
				                          buffer_minutes, price, currency, price_from, online_bookable, status,
				                          created_at, updated_at, version)
				VALUES (?, ?, 1, ?, 'Cut', ?, 0, 100, ?, false, true, 'ACTIVE', now(), now(), 0)
				""", id, salonId, sectionId, durationMinutes, currency);
		return id;
	}

	private void insertOffering(UUID salonId, UUID serviceId, UUID branchId) {
		jdbc.update("""
				INSERT INTO service_offering (id, salon_id, service_id, branch_id, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, now(), now(), 0)
				""", UUID.randomUUID(), salonId, serviceId, branchId);
	}

	private void insertSkill(UUID salonId, UUID staffId, UUID serviceId) {
		jdbc.update("""
				INSERT INTO staff_skill (id, salon_id, staff_id, service_id, created_at, version)
				VALUES (?, ?, ?, ?, now(), 0)
				""", UUID.randomUUID(), salonId, staffId, serviceId);
	}

	/** Runs the statement in its own transaction and expects the named constraint to reject it. */
	private void assertViolates(String constraint, Runnable statement) {
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> statement.run()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

}
