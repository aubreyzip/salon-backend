package com.salon.scheduling.domain;

import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;

public final class SchedulingErrors {

	private SchedulingErrors() {
	}

	public static ApiException branchNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "The branch does not exist.");
	}

	public static ApiException staffNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "The staff member does not exist.");
	}

	public static ApiException timeOffNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "TIME_OFF_NOT_FOUND", "The time off does not exist.");
	}

	public static ApiException closureNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CLOSURE_NOT_FOUND", "The closure does not exist.");
	}

	public static ApiException invalidInterval() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INTERVAL",
				"Intervals need a start before the end, in whole minutes, within one day.");
	}

	public static ApiException overlappingIntervals() {
		return new ApiException(HttpStatus.BAD_REQUEST, "OVERLAPPING_INTERVALS",
				"Intervals on the same day must not overlap.");
	}

	public static ApiException invalidDateRange(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", detail);
	}

	public static ApiException invalidTimeRange(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", detail);
	}

	public static ApiException invalidStart(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_START", detail);
	}

	public static ApiException invalidServices(int maxServices) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SERVICES",
				"Request between 1 and " + maxServices + " services.");
	}

	public static ApiException overlappingPeriods() {
		return new ApiException(HttpStatus.BAD_REQUEST, "OVERLAPPING_PERIODS",
				"Working-hour periods at the same branch must not overlap in dates.");
	}

	public static ApiException workingHoursConflict(String detail) {
		return new ApiException(HttpStatus.CONFLICT, "WORKING_HOURS_CONFLICT", detail);
	}

	public static ApiException staffInactive() {
		return new ApiException(HttpStatus.CONFLICT, "STAFF_INACTIVE", "This staff member is inactive.");
	}

	public static ApiException staffNotAssignedToBranch() {
		return new ApiException(HttpStatus.CONFLICT, "STAFF_NOT_ASSIGNED_TO_BRANCH",
				"Assign the staff member to the branch before adding working hours there.");
	}

}
