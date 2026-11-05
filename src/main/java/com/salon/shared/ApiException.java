package com.salon.shared;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A business error that is reported to API clients as an RFC 9457 problem detail with a stable
 * machine-readable {@code code}. Clients localize messages based on the code, not the detail.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final Map<String, Object> properties;

	public ApiException(HttpStatus status, String code, String detail) {
		this(status, code, detail, Map.of());
	}

	/**
	 * @param properties extra problem-detail members (e.g. the conflicting resources); must not
	 * contain personal data the caller may not see
	 */
	public ApiException(HttpStatus status, String code, String detail, Map<String, Object> properties) {
		super(detail);
		this.status = status;
		this.code = code;
		this.properties = Map.copyOf(properties);
	}

	public Map<String, Object> getProperties() {
		return properties;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

}
