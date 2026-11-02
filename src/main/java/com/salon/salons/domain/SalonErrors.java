package com.salon.salons.domain;

import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;

public final class SalonErrors {

	private SalonErrors() {
	}

	public static ApiException salonNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "SALON_NOT_FOUND", "The salon does not exist.");
	}

	public static ApiException branchNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "The branch does not exist.");
	}

	public static ApiException staffNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "The staff member does not exist.");
	}

	public static ApiException insufficientPermission() {
		return new ApiException(HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION",
				"Your role in this salon does not allow this action.");
	}

	public static ApiException branchNotAssigned() {
		return new ApiException(HttpStatus.FORBIDDEN, "BRANCH_NOT_ASSIGNED",
				"You are not assigned to this branch.");
	}

	public static ApiException roleChangeNotAllowed() {
		return new ApiException(HttpStatus.FORBIDDEN, "ROLE_CHANGE_NOT_ALLOWED",
				"Only owners can manage members with the same or a higher role.");
	}

	public static ApiException salonSuspended() {
		return new ApiException(HttpStatus.CONFLICT, "SALON_SUSPENDED",
				"The salon is suspended and cannot be changed.");
	}

	public static ApiException invalidStatusTransition(SalonStatus from, String action) {
		return new ApiException(HttpStatus.CONFLICT, "INVALID_SALON_STATUS",
				"Cannot " + action + " a salon in status " + from + ".");
	}

	public static ApiException notReadyForReview() {
		return new ApiException(HttpStatus.CONFLICT, "SALON_NOT_READY_FOR_REVIEW",
				"Add at least one active branch with a map location before submitting the salon for review.");
	}

	public static ApiException lastOwner() {
		return new ApiException(HttpStatus.CONFLICT, "LAST_OWNER", "A salon must keep at least one active owner.");
	}

	public static ApiException unknownProvince() {
		return new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PROVINCE", "The province does not exist.");
	}

	public static ApiException invalidTimeZone() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_ZONE",
				"The time zone must be an IANA region id such as Europe/Istanbul.");
	}

	public static ApiException invalidCoordinates() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COORDINATES",
				"Latitude and longitude must be given together.");
	}

	public static ApiException invalidBookingPolicy(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BOOKING_POLICY", detail);
	}

	public static ApiException branchNotInSalon() {
		return new ApiException(HttpStatus.BAD_REQUEST, "BRANCH_NOT_IN_SALON",
				"Staff can only be assigned to branches of the same salon.");
	}

	public static ApiException staffAlreadyLinked() {
		return new ApiException(HttpStatus.CONFLICT, "STAFF_ALREADY_LINKED",
				"This staff member is already linked to a user account.");
	}

	public static ApiException staffInactive() {
		return new ApiException(HttpStatus.CONFLICT, "STAFF_INACTIVE", "This staff member is inactive.");
	}

	public static ApiException invitationInvalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVITATION_INVALID",
				"The invitation is invalid, expired or already used.");
	}

	public static ApiException invitationEmailMismatch() {
		return new ApiException(HttpStatus.FORBIDDEN, "INVITATION_EMAIL_MISMATCH",
				"This invitation was sent to a different email address.");
	}

	public static ApiException alreadyAMember() {
		return new ApiException(HttpStatus.CONFLICT, "ALREADY_A_MEMBER", "You are already a member of this salon.");
	}

	public static ApiException formerMember() {
		return new ApiException(HttpStatus.CONFLICT, "FORMER_MEMBER",
				"You were a member of this salon before. Ask the salon to reactivate your membership.");
	}

}
