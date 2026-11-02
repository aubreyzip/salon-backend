package com.salon.salons;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import com.salon.salons.domain.StaffMember;
import com.salon.salons.domain.StaffMemberRepository;
import com.salon.salons.domain.StaffProfile;
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
 * Constraints of the salons schema, checked directly against PostgreSQL with application checks
 * bypassed (ADR-0002, ADR-0004). Each assertion names the constraint that must fire.
 */
@IntegrationTest
class SalonDatabaseConstraintsIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	StaffMemberRepository staff;

	private UUID userId;

	@BeforeEach
	void createUser() throws Exception {
		userId = api.signUp("Constraint User").id();
	}

	@Test
	void staffCannotBeAssignedToABranchOfAnotherSalonThroughJpa() throws Exception {
		TestUser owner = api.signUp("Owner");
		UUID salonA = api.createSalon(owner, "Constraint A");
		UUID branchA = api.createBranch(owner, salonA);
		UUID branchB = api.createBranch(owner, api.createSalon(owner, "Constraint B"));

		assertThatCode(() -> saveStaffWithBranch(salonA, branchA)).doesNotThrowAnyException();
		assertThatThrownBy(() -> saveStaffWithBranch(salonA, branchB))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("staff_branch_branch_fk");
	}

	@Test
	void staffBranchRowsMustMatchTheStaffMembersSalon() {
		UUID salonA = insertSalon();
		UUID salonB = insertSalon();
		UUID staffA = insertStaff(salonA, null, "STAFF");
		UUID branchB = insertBranch(salonB, "branch");

		// Claiming salon B for a staff member of salon A breaks the staff side of the composite key.
		assertViolates("staff_branch_staff_fk",
				() -> jdbc.update("INSERT INTO staff_branch (staff_id, salon_id, branch_id) VALUES (?, ?, ?)", staffA,
						salonB, branchB));
	}

	@Test
	void aUserHasAtMostOneMembershipPerSalon() {
		UUID salonA = insertSalon();
		UUID salonB = insertSalon();
		insertStaff(salonA, userId, "STAFF");

		assertViolates("staff_member_salon_user_uk", () -> insertStaff(salonA, userId, "MANAGER"));
		assertThatCode(() -> insertStaff(salonB, userId, "STAFF")).doesNotThrowAnyException();
		// Profiles without an account are not limited.
		assertThatCode(() -> {
			insertStaff(salonA, null, "STAFF");
			insertStaff(salonA, null, "STAFF");
		}).doesNotThrowAnyException();
	}

	@Test
	void invitationsMustReferenceStaffOfTheSameSalon() {
		UUID salonA = insertSalon();
		UUID salonB = insertSalon();
		UUID staffB = insertStaff(salonB, null, "STAFF");

		assertViolates("staff_invitation_staff_fk", () -> insertInvitation(salonA, staffB, null, null));
	}

	@Test
	void acceptedInvitationsRecordWhoAcceptedThem() {
		UUID salonId = insertSalon();
		UUID staffId = insertStaff(salonId, null, "STAFF");

		assertViolates("staff_invitation_acceptance_ck", () -> insertInvitation(salonId, staffId, Instant.now(), null));
		assertViolates("staff_invitation_acceptance_ck", () -> insertInvitation(salonId, staffId, null, userId));
		assertViolates("staff_invitation_token_uk", () -> {
			jdbc.update(INSERT_INVITATION, UUID.randomUUID(), salonId, staffId, "same-hash", userId, null, null);
			jdbc.update(INSERT_INVITATION, UUID.randomUUID(), salonId, staffId, "same-hash", userId, null, null);
		});
	}

	@Test
	void slugsAreUniqueAndWellFormed() {
		String slug = "slug-" + UUID.randomUUID();
		insertSalon(slug);
		UUID salonId = insertSalon();
		UUID otherSalonId = insertSalon();
		insertBranch(salonId, "merkez");

		assertViolates("salon_slug_uk", () -> insertSalon(slug));
		assertViolates("salon_slug_format_ck", () -> insertSalon("Not A Slug"));
		assertViolates("salon_slug_format_ck", () -> insertSalon("trailing-"));
		assertViolates("branch_salon_slug_uk", () -> insertBranch(salonId, "merkez"));
		assertThatCode(() -> insertBranch(otherSalonId, "merkez")).doesNotThrowAnyException();
	}

	@Test
	void branchValuesAreChecked() {
		UUID salonId = insertSalon();

		assertViolates("branch_coordinates_ck", () -> insertBranch(salonId, "a", 34, 40.99, null, 15));
		assertViolates("branch_latitude_ck", () -> insertBranch(salonId, "b", 34, 91.0, 29.0, 15));
		assertViolates("branch_slot_interval_ck", () -> insertBranch(salonId, "c", 34, null, null, 7));
		assertViolates("branch_province_fk", () -> insertBranch(salonId, "d", 82, null, null, 15));
		assertViolates("branch_salon_fk", () -> insertBranch(UUID.randomUUID(), "e", 34, null, null, 15));
	}

	@Test
	void rolesAndStatusesAreRestrictedToKnownValues() {
		UUID salonId = insertSalon();

		assertViolates("staff_member_role_ck", () -> insertStaff(salonId, null, "ADMIN"));
		assertViolates("salon_status_ck", () -> jdbc.update("UPDATE salon SET status = 'DELETED' WHERE id = ?",
				salonId));
	}

	@Test
	void allEightyOneProvincesAreSeeded() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM province", Integer.class)).isEqualTo(81);
		assertThat(jdbc.queryForObject("SELECT min(id) || '-' || max(id) FROM province", String.class))
			.isEqualTo("1-81");
		assertThat(jdbc.queryForObject("SELECT name FROM province WHERE id = 34", String.class)).isEqualTo("İstanbul");
	}

	private static final String INSERT_INVITATION = """
			INSERT INTO staff_invitation (id, salon_id, staff_id, email, token_hash, invited_by_user_id,
			                              expires_at, accepted_at, accepted_by_user_id, created_at, version)
			VALUES (?, ?, ?, 'x@example.com', ?, ?, now() + interval '7 days', ?, ?, now(), 0)
			""";

	private void insertInvitation(UUID salonId, UUID staffId, Instant acceptedAt, UUID acceptedBy) {
		jdbc.update(INSERT_INVITATION, UUID.randomUUID(), salonId, staffId, UUID.randomUUID().toString(), userId,
				acceptedAt == null ? null : Timestamp.from(acceptedAt), acceptedBy);
	}

	private UUID insertSalon() {
		return insertSalon("s-" + UUID.randomUUID());
	}

	private UUID insertSalon(String slug) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO salon (id, name, slug, status, created_by_user_id, created_at, updated_at, version)
				VALUES (?, 'Salon', ?, 'DRAFT', ?, now(), now(), 0)
				""", id, slug, userId);
		return id;
	}

	private UUID insertBranch(UUID salonId, String slug) {
		return insertBranch(salonId, slug, 34, null, null, 15);
	}

	private UUID insertBranch(UUID salonId, String slug, int provinceId, Double latitude, Double longitude,
			int slotInterval) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO branch (id, salon_id, name, slug, province_id, district, address_line, latitude, longitude,
				                    time_zone, status, auto_confirm, slot_interval_minutes, min_notice_minutes,
				                    max_advance_days, cancellation_cutoff_minutes,
				                    pending_confirmation_timeout_minutes, created_at, updated_at, version)
				VALUES (?, ?, 'Branch', ?, ?, 'Kadıköy', 'Moda', ?, ?, 'Europe/Istanbul', 'ACTIVE', true, ?, 60, 60,
				        120, 120, now(), now(), 0)
				""", id, salonId, slug, provinceId, latitude, longitude, slotInterval);
		return id;
	}

	private UUID insertStaff(UUID salonId, UUID memberUserId, String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_member (id, salon_id, user_id, display_name, role, status, bookable, created_at,
				                          updated_at, version)
				VALUES (?, ?, ?, 'Staff', ?, 'ACTIVE', true, now(), now(), 0)
				""", id, salonId, memberUserId, role);
		return id;
	}

	/** Runs the statement in its own transaction and expects the named constraint to reject it. */
	private void assertViolates(String constraint, Runnable statement) {
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> statement.run()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

	private void saveStaffWithBranch(UUID salonId, UUID branchId) {
		transactionTemplate.executeWithoutResult(status -> {
			Instant now = Instant.now();
			StaffMember member = new StaffMember(salonId, new StaffProfile("Direct", null, null, true),
					StaffRole.STAFF, now);
			member.assignBranches(Set.of(branchId), now);
			staff.saveAndFlush(member);
		});
	}

}
