package com.salon.shared;

import org.springframework.http.HttpStatus;

/**
 * A business error that is reported to API clients as an RFC 9457 problem detail with a stable
 * machine-readable {@code code}. Clients localize messages based on the code, not the detail.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

}
