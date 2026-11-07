package com.salon.scheduling.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.salon.scheduling.application.AvailabilityService;
import com.salon.scheduling.application.SchedulingViews.AvailabilityView;
import com.salon.shared.CurrentUserId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Salon-side availability for walk-ins and phone bookings (ADR-0012 decision 20, ADR-0013 decision
 * 37): every service offered at the branch (also those not bookable online), inactive branches too,
 * up to 15 minutes in the past and 365 days ahead. Salon bookings may also start off this grid.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/branches/{branchId}/availability")
class SalonAvailabilityController {

	private final AvailabilityService availability;

	SalonAvailabilityController(AvailabilityService availability) {
		this.availability = availability;
	}

	/**
	 * @param staffId only this staff member; required for members who manage only their own
	 * appointments
	 */
	@GetMapping
	AvailabilityView availability(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId,
			@RequestParam List<UUID> serviceIds, @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to, @RequestParam(required = false) UUID staffId) {
		return availability.salonAvailability(salonId, branchId, userId, serviceIds, from, to, staffId);
	}

}
