package com.salon.booking.domain;

import java.util.Map;

import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;

public final class BookingErrors {

	private BookingErrors() {
	}

	public static ApiException appointmentNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "The appointment does not exist.");
	}

	public static ApiException clientNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "The client does not exist.");
	}

	public static ApiException branchNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "The branch does not exist.");
	}

	public static ApiException branchNotBookable() {
		return new ApiException(HttpStatus.CONFLICT, "BRANCH_NOT_BOOKABLE",
				"The branch does not take online bookings at the moment. Please contact the salon.");
	}

	public static ApiException phoneNotVerified() {
		return new ApiException(HttpStatus.FORBIDDEN, "PHONE_NOT_VERIFIED",
				"Verify your phone number before booking.");
	}

	public static ApiException accountInactive() {
		return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This account cannot book appointments.");
	}

	public static ApiException slotUnavailable() {
		return new ApiException(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE",
				"This time is no longer available. Please choose another one.");
	}

	public static ApiException customerOverlap() {
		return new ApiException(HttpStatus.CONFLICT, "CUSTOMER_OVERLAP",
				"The customer already has an appointment at this time.");
	}

	public static ApiException customerLimit(int limit) {
		return new ApiException(HttpStatus.CONFLICT, "CUSTOMER_APPOINTMENT_LIMIT",
				"The customer already has " + limit + " upcoming appointments.", Map.of("limit", limit));
	}

	public static ApiException cancellationWindowClosed() {
		return new ApiException(HttpStatus.CONFLICT, "CANCELLATION_WINDOW_CLOSED",
				"It is too late to change this appointment online. Please contact the salon.");
	}

	public static ApiException invalidTransition(String detail) {
		return new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", detail);
	}

	public static ApiException notStarted() {
		return new ApiException(HttpStatus.CONFLICT, "APPOINTMENT_NOT_STARTED",
				"The outcome can be recorded once the appointment has started.");
	}

	public static ApiException duplicateService() {
		return new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SERVICE", "Each service can be booked only once.");
	}

	public static ApiException invalidPhoneNumber() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER",
				"Enter a Turkish mobile number, e.g. 0532 123 45 67.");
	}

	public static ApiException idempotencyKeyRequired() {
		return new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
				"Send an Idempotency-Key header (1-100 characters, e.g. a UUID).");
	}

	public static ApiException idempotencyKeyReused() {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
				"This Idempotency-Key was already used for a different request.");
	}

}
