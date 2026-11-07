package com.salon.booking.application;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How database errors of a booking attempt are classified (ADR-0013).
 */
class BookingExecutorTest {

	@Test
	void exclusionAndUniqueViolationsAreRecognizedByConstraintName() {
		assertThat(BookingExecutor.violatedConstraint(wrapped("23P01",
				"conflicting key value violates exclusion constraint \"appointment_no_staff_overlap\"")))
			.isEqualTo(BookingExecutor.STAFF_OVERLAP);
		assertThat(BookingExecutor.violatedConstraint(wrapped("23P01",
				"conflicting key value violates exclusion constraint \"appointment_no_customer_overlap\"")))
			.isEqualTo(BookingExecutor.CUSTOMER_OVERLAP);
		assertThat(BookingExecutor.violatedConstraint(
				wrapped("23505", "duplicate key value violates unique constraint \"idempotency_record_user_key_uk\"")))
			.isEqualTo(BookingExecutor.IDEMPOTENCY_KEY);
	}

	@Test
	void deadlocksAndSerializationFailuresCanBeRetried() {
		assertThat(BookingExecutor.violatedConstraint(wrapped("40P01", "deadlock detected")))
			.isEqualTo(BookingExecutor.RETRYABLE);
		assertThat(BookingExecutor.violatedConstraint(wrapped("40001", "could not serialize access")))
			.isEqualTo(BookingExecutor.RETRYABLE);
	}

	@Test
	void otherErrorsAreNotTranslated() {
		assertThat(BookingExecutor.violatedConstraint(
				wrapped("23503", "insert violates foreign key constraint \"appointment_client_fk\"")))
			.isNull();
		assertThat(BookingExecutor.violatedConstraint(new DataIntegrityViolationException("no cause"))).isNull();
	}

	private static Exception wrapped(String sqlState, String message) {
		return new DataIntegrityViolationException("could not execute statement",
				new RuntimeException("Hibernate", new SQLException(message, sqlState)));
	}

}
