package com.salon.identity.application;

import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Error codes of the identity module. Messages are deliberately generic where a more specific
 * message would reveal whether an account exists.
 */
final class IdentityErrors {

	private IdentityErrors() {
	}

	static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
	}

	static ApiException loginLocked() {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_TEMPORARILY_LOCKED",
				"Too many failed login attempts. Try again later.");
	}

	static ApiException accountDisabled() {
		return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account is disabled.");
	}

	static ApiException emailNotVerified() {
		return new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "The email address is not verified yet.");
	}

	static ApiException invalidRefreshToken() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
				"The refresh token is invalid or expired.");
	}

	static ApiException invalidOrExpiredCode() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_CODE",
				"The verification code is invalid or expired.");
	}

	static ApiException verificationRateLimited() {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_RATE_LIMITED",
				"Too many verification codes requested. Try again later.");
	}

	static ApiException invalidPhoneNumber() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER",
				"Enter a valid Turkish mobile number.");
	}

	static ApiException phoneAlreadyVerified() {
		return new ApiException(HttpStatus.CONFLICT, "PHONE_ALREADY_VERIFIED",
				"This phone number is already verified on your account.");
	}

	static ApiException phoneAlreadyInUse() {
		return new ApiException(HttpStatus.CONFLICT, "PHONE_ALREADY_IN_USE",
				"This phone number is already used by another account.");
	}

	static ApiException passwordTooLong() {
		return new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG",
				"The password is too long. Use at most 72 bytes.");
	}

	static ApiException userNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist.");
	}

}
