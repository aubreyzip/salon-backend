package com.salon.scheduling.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.salon.scheduling.application.AvailabilityService;
import com.salon.scheduling.application.SchedulingViews.AvailabilityView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public availability for customers. Inactive branches and branches of salons that are not active
 * are not found.
 */
@RestController
@RequestMapping("/api/v1/public/branches/{branchId}/availability")
class PublicAvailabilityController {

	private final AvailabilityService availability;

	PublicAvailabilityController(AvailabilityService availability) {
		this.availability = availability;
	}

	/**
	 * Bookable start times for the services (one staff member performs all of them), per day from
	 * {@code from} to {@code to} (inclusive, branch-local dates, at most 31 days).
	 * @param serviceIds 1 to 5 services, e.g. {@code serviceIds=a,b}
	 * @param staffId only this staff member; omit for anyone available
	 */
	@GetMapping
	AvailabilityView availability(@PathVariable UUID branchId, @RequestParam List<UUID> serviceIds,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to, @RequestParam(required = false) UUID staffId) {
		return availability.publicAvailability(branchId, serviceIds, from, to, staffId);
	}

}
