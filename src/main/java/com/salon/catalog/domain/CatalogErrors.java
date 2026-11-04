package com.salon.catalog.domain;

import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;

public final class CatalogErrors {

	private CatalogErrors() {
	}

	public static ApiException branchNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "The branch does not exist.");
	}

	public static ApiException staffNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "The staff member does not exist.");
	}

	public static ApiException serviceNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "The service does not exist.");
	}

	public static ApiException sectionNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "SERVICE_SECTION_NOT_FOUND",
				"The service section does not exist.");
	}

	public static ApiException unknownCategory() {
		return new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SERVICE_CATEGORY",
				"The service category does not exist.");
	}

	public static ApiException unknownSection() {
		return new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SERVICE_SECTION",
				"The service section does not exist in this salon.");
	}

	public static ApiException serviceNotInSalon() {
		return new ApiException(HttpStatus.BAD_REQUEST, "SERVICE_NOT_IN_SALON",
				"Skills can only refer to services of the same salon.");
	}

	public static ApiException duplicateService() {
		return new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SERVICE", "Each service may appear only once.");
	}

	public static ApiException serviceArchived() {
		return new ApiException(HttpStatus.CONFLICT, "SERVICE_ARCHIVED",
				"The service is archived. Restore it before changing where it is offered.");
	}

	public static ApiException serviceNotOffered() {
		return new ApiException(HttpStatus.BAD_REQUEST, "SERVICE_NOT_OFFERED",
				"One or more services are not offered at this branch.");
	}

}
