package com.salon.booking.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-customer lock row (ADR-0012 decision 24, ADR-0013): every booking and reschedule for an
 * account-linked customer locks it {@code FOR UPDATE} first, so the overlap and limit checks for one
 * customer run one transaction at a time, across all salons. Lock order: customer, then salon row.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CustomerLocks {

	private final JdbcClient jdbc;

	private final Clock clock;

	CustomerLocks(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Locks the customer until the transaction ends (creating the row on first use). */
	void lock(UUID userId) {
		jdbc.sql("INSERT INTO booking_customer_lock (user_id, created_at) VALUES (?, ?) ON CONFLICT DO NOTHING")
			.params(userId, Timestamp.from(clock.instant()))
			.update();
		jdbc.sql("SELECT user_id FROM booking_customer_lock WHERE user_id = ? FOR UPDATE")
			.param(userId)
			.query(UUID.class)
			.single();
	}

}
