package com.salon.salons.web;

import java.util.UUID;

import com.salon.salons.application.SalonService;
import com.salon.salons.application.SalonView;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/salons")
class SalonController {

	private final SalonService salons;

	SalonController(SalonService salons) {
		this.salons = salons;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SalonView create(@CurrentUserId UUID userId, @Valid @RequestBody SalonRequest request) {
		return salons.create(userId, request.name(), request.description());
	}

	@GetMapping("/{salonId}")
	SalonView get(@CurrentUserId UUID userId, @PathVariable UUID salonId) {
		return salons.get(salonId, userId);
	}

	@PutMapping("/{salonId}")
	SalonView update(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@Valid @RequestBody SalonRequest request) {
		return salons.update(salonId, userId, request.name(), request.description());
	}

	@PostMapping("/{salonId}/submit-for-review")
	SalonView submitForReview(@CurrentUserId UUID userId, @PathVariable UUID salonId) {
		return salons.submitForReview(salonId, userId);
	}

	record SalonRequest(@NotBlank @Size(min = 2, max = 100) String name, @Size(max = 1000) String description) {
	}

}
