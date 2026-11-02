package com.salon.shared.web;

import java.util.List;
import java.util.Map;

import com.salon.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 problem details. Every response carries a stable {@code code}
 * property that clients can rely on.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ProblemDetail handleApiException(ApiException exception) {
		return problem(exception.getStatus(), exception.getCode(), exception.getMessage());
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail handleOptimisticLocking(ObjectOptimisticLockingFailureException exception) {
		return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
				"The resource was modified concurrently. Please retry.");
	}

	/** Unknown property in a {@code sort} request parameter. */
	@ExceptionHandler(PropertyReferenceException.class)
	ProblemDetail handleInvalidSort(PropertyReferenceException exception) {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Cannot sort by '" + exception.getPropertyName() + "'.");
	}

	/** Unique or foreign-key violations that slipped past application checks (e.g. concurrent requests). */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
		log.warn("Data integrity violation: {}", exception.getMostSpecificCause().getMessage());
		return problem(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with the current state.");
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception exception) throws Exception {
		if (exception instanceof AccessDeniedException || exception instanceof AuthenticationException) {
			// Let Spring Security's entry point / access denied handler produce 401/403.
			throw exception;
		}
		log.error("Unhandled exception", exception);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid.");
		List<Map<String, String>> errors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.toList();
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
			WebRequest request) {
		if (body instanceof ProblemDetail problem && (problem.getProperties() == null
				|| !problem.getProperties().containsKey("code"))) {
			problem.setProperty("code", defaultCode(statusCode));
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

	private static String defaultCode(HttpStatusCode status) {
		HttpStatus resolved = HttpStatus.resolve(status.value());
		return resolved != null ? resolved.name() : "HTTP_" + status.value();
	}

}
