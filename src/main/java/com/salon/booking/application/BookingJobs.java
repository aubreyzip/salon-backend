package com.salon.booking.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.booking.domain.Appointment;
import com.salon.booking.domain.AppointmentRepository;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.booking.domain.HistoryAction;
import com.salon.booking.domain.IdempotencyRecordRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background work of the booking module (ADR-0009, ADR-0013). Safe on several instances without
 * ShedLock: due appointments are selected {@code FOR UPDATE SKIP LOCKED}, so two instances never
 * expire the same appointment, and a customer cancelling at the same moment is skipped (or wins
 * through optimistic locking). Scheduling is in {@code BookingScheduler}; tests call these directly.
 */
@Service
public class BookingJobs {

	static final int BATCH_SIZE = 100;

	/** How long a used {@code Idempotency-Key} is kept (decision 41). */
	static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

	private final AppointmentRepository appointments;

	private final IdempotencyRecordRepository idempotencyRecords;

	private final AppointmentRecorder recorder;

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final Clock clock;

	BookingJobs(AppointmentRepository appointments, IdempotencyRecordRepository idempotencyRecords,
			AppointmentRecorder recorder, JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
		this.appointments = appointments;
		this.idempotencyRecords = idempotencyRecords;
		this.recorder = recorder;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Expires pending appointments whose confirmation deadline has passed (decision 31), in batches.
	 * @return how many were expired
	 */
	public int expireDuePendingAppointments() {
		int total = 0;
		int expired;
		do {
			expired = transactions.execute(status -> expireBatch());
			total += expired;
		}
		while (expired == BATCH_SIZE);
		return total;
	}

	/** Deletes idempotency records older than 24 hours. */
	public int deleteOldIdempotencyRecords() {
		return transactions
			.execute(status -> idempotencyRecords.deleteCreatedBefore(clock.instant().minus(IDEMPOTENCY_RETENTION)));
	}

	private int expireBatch() {
		Instant now = clock.instant();
		List<UUID> due = jdbc.sql("""
				SELECT id FROM appointment
				WHERE status = 'PENDING' AND confirmation_deadline <= ?
				ORDER BY confirmation_deadline
				LIMIT ?
				FOR UPDATE SKIP LOCKED
				""").params(Timestamp.from(now), BATCH_SIZE).query(UUID.class).list();
		for (UUID id : due) {
			Appointment appointment = appointments.findById(id).orElseThrow();
			appointment.expire(now);
			recorder.record(appointment, HistoryAction.EXPIRED, AppointmentStatus.PENDING, null, null);
		}
		return due.size();
	}

}
