package com.salon.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.salon.booking.domain.BookingErrors;
import com.salon.booking.domain.IdempotencyRecord;
import com.salon.booking.domain.IdempotencyRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code Idempotency-Key} handling (ADR-0013 decision 41). {@link #claim} runs first in the booking
 * transaction: a committed record replays its result; otherwise the key is inserted and flushed. A
 * concurrent request with the same key then waits on the unique index and fails once the first
 * commits, and {@link BookingExecutor} replays the result. A failed request rolls its key back.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class Idempotency {

	static final int MAX_KEY_LENGTH = 100;

	private final IdempotencyRecordRepository records;

	private final Clock clock;

	Idempotency(IdempotencyRecordRepository records, Clock clock) {
		this.records = records;
		this.clock = clock;
	}

	/** The request in the form the key is bound to. */
	record Request(UUID userId, String key, String scope, String hash) {

		/**
		 * @param scope the endpoint and target, e.g. {@code customer.book}
		 * @param body the request body (a record, so its string form is deterministic)
		 * @throws com.salon.shared.ApiException {@code 400 IDEMPOTENCY_KEY_REQUIRED}
		 */
		static Request of(UUID userId, String key, String scope, Object body) {
			if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
				throw BookingErrors.idempotencyKeyRequired();
			}
			return new Request(userId, key.strip(), scope, sha256(scope + "|" + body));
		}

	}

	/**
	 * @return the appointment of an earlier, completed request with this key; empty if the key was
	 * claimed now
	 * @throws com.salon.shared.ApiException {@code 422 IDEMPOTENCY_KEY_REUSED} for a different request
	 */
	Optional<UUID> claim(Request request) {
		Optional<UUID> earlier = replay(request);
		if (earlier.isEmpty()) {
			records.saveAndFlush(
					new IdempotencyRecord(request.userId(), request.key(), request.scope(), request.hash(), clock.instant()));
		}
		return earlier;
	}

	void complete(Request request, UUID appointmentId, int status) {
		records.findByUserIdAndKey(request.userId(), request.key())
			.orElseThrow()
			.complete(appointmentId, status);
	}

	/** The result of a committed request with this key, if any. */
	Optional<UUID> replay(Request request) {
		return records.findByUserIdAndKey(request.userId(), request.key()).map(record -> {
			if (!record.matches(request.scope(), request.hash())) {
				throw BookingErrors.idempotencyKeyReused();
			}
			return record.getAppointmentId();
		});
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
