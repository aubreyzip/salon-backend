package com.salon.salons;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The explicit emergency override of a change that conflicts with appointments (ADR-0012 decisions
 * 21, 25 and 26, ADR-0013 decision 38). Sent as the optional {@code conflictOverride} request member.
 *
 * @param reason why the appointments are affected; stored with the flagged appointments
 */
public record ConflictOverride(@NotBlank @Size(min = 3, max = 500) String reason) {

	public ConflictOverride {
		reason = reason == null ? null : reason.strip();
	}

	/**
	 * The optional JSON body of changes that have no other body (archive, deactivate, remove).
	 */
	public record Body(@Valid ConflictOverride conflictOverride) {

		/** The override of an optional body, or {@code null}. */
		public static ConflictOverride of(Body body) {
			return body == null ? null : body.conflictOverride();
		}

	}

}
