package com.salon.booking.application;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.salon.booking.application.Idempotency.Request;
import com.salon.booking.domain.BookingErrors;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a booking or reschedule in its own transaction (ADR-0013):
 * <ul>
 * <li>{@code Idempotency-Key}: claimed first; a concurrent duplicate replays the first result;</li>
 * <li>"any staff member" (decision 28): if the exclusion constraint rejects the picked staff
 * member (a concurrent booking won), the next candidate is tried, at most {@value #MAX_ATTEMPTS}
 * attempts in total;</li>
 * <li>deadlocks: PostgreSQL can deadlock concurrent inserts that conflict on an exclusion constraint
 * (each waits for the other's uncommitted row); it aborts one, which is retried and then sees the
 * winner;</li>
 * <li>constraint violations become {@code 409 SLOT_UNAVAILABLE} or {@code 409 CUSTOMER_OVERLAP}.</li>
 * </ul>
 */
@Component
class BookingExecutor {

	/** Staff members tried for "any staff member" (decision 28). */
	static final int MAX_ATTEMPTS = 3;

	/** Retries after a deadlock or serialization failure. */
	static final int MAX_DEADLOCK_RETRIES = 3;

	static final String STAFF_OVERLAP = "appointment_no_staff_overlap";

	static final String CUSTOMER_OVERLAP = "appointment_no_customer_overlap";

	static final String IDEMPOTENCY_KEY = "idempotency_record_user_key_uk";

	/** SQLSTATE of a deadlock or serialization failure: the attempt can be retried. */
	static final String RETRYABLE = "retryable";

	private final TransactionTemplate transactions;

	private final TransactionTemplate readOnly;

	private final Idempotency idempotency;

	BookingExecutor(PlatformTransactionManager transactionManager, Idempotency idempotency) {
		this.transactions = new TransactionTemplate(transactionManager);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.idempotency = idempotency;
	}

	/** State shared by the attempts of one request. */
	static final class Attempt {

		private final Set<UUID> excludedStaff = new HashSet<>();

		private UUID pickedStaff;

		private boolean anyStaff;

		boolean excludes(UUID staffId) {
			return excludedStaff.contains(staffId);
		}

		/**
		 * @param any the staff member was picked automatically (decision 28), so another may be tried
		 */
		void picked(UUID staffId, boolean any) {
			pickedStaff = staffId;
			anyStaff = any;
		}

	}

	/**
	 * @param work the booking; returns the appointment id
	 * @return the appointment id (of this request, or of the earlier one with the same key)
	 */
	UUID execute(Request request, int successStatus, Function<Attempt, UUID> work) {
		Attempt attempt = new Attempt();
		int number = 1;
		int deadlocks = 0;
		while (true) {
			try {
				return transactions.execute(status -> {
					Optional<UUID> earlier = idempotency.claim(request);
					if (earlier.isPresent()) {
						return earlier.get();
					}
					UUID appointmentId = work.apply(attempt);
					idempotency.complete(request, appointmentId, successStatus);
					return appointmentId;
				});
			}
			catch (DataAccessException ex) {
				String constraint = violatedConstraint(ex);
				if (RETRYABLE.equals(constraint)) {
					if (++deadlocks <= MAX_DEADLOCK_RETRIES) {
						continue;
					}
					throw BookingErrors.slotUnavailable();
				}
				if (IDEMPOTENCY_KEY.equals(constraint)) {
					return transactions.execute(status -> idempotency.replay(request))
						.orElseThrow(BookingErrors::slotUnavailable);
				}
				if (STAFF_OVERLAP.equals(constraint)) {
					if (attempt.anyStaff && attempt.pickedStaff != null && number < MAX_ATTEMPTS) {
						attempt.excludedStaff.add(attempt.pickedStaff);
						number++;
						continue;
					}
					throw BookingErrors.slotUnavailable();
				}
				if (CUSTOMER_OVERLAP.equals(constraint)) {
					throw BookingErrors.customerOverlap();
				}
				throw ex;
			}
		}
	}

	/** Runs a read in a read-only transaction (e.g. the response view after a booking). */
	<T> T read(Supplier<T> query) {
		return readOnly.execute(status -> query.get());
	}

	/**
	 * The name of the unique or exclusion constraint a database error reports, {@link #RETRYABLE} for
	 * deadlocks and serialization failures, or {@code null}.
	 */
	static String violatedConstraint(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && ("40P01".equals(sql.getSQLState())
					|| "40001".equals(sql.getSQLState()))) {
				return RETRYABLE;
			}
			if (cause instanceof SQLException sql && sql.getSQLState() != null
					&& (sql.getSQLState().equals("23505") || sql.getSQLState().equals("23P01"))) {
				String message = String.valueOf(sql.getMessage());
				for (String name : new String[] { STAFF_OVERLAP, CUSTOMER_OVERLAP, IDEMPOTENCY_KEY }) {
					if (message.contains(name)) {
						return name;
					}
				}
			}
		}
		return null;
	}

}
