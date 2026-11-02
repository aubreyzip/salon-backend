package com.salon.salons.web;

import java.util.UUID;

import com.salon.salons.application.SalonAdminService;
import com.salon.salons.application.SalonView;
import com.salon.salons.domain.SalonStatus;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Salon review and moderation for platform admins ({@code /api/v1/admin/**} requires
 * {@code PLATFORM_ADMIN}).
 */
@RestController
@RequestMapping("/api/v1/admin/salons")
class AdminSalonController {

	private final SalonAdminService admin;

	AdminSalonController(SalonAdminService admin) {
		this.admin = admin;
	}

	@GetMapping
	Page<SalonView> list(@RequestParam(required = false) SalonStatus status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
		return admin.list(status, pageable);
	}

	@PostMapping("/{salonId}/approve")
	SalonView approve(@CurrentUserId UUID adminId, @PathVariable UUID salonId) {
		return admin.approve(salonId, adminId);
	}

	@PostMapping("/{salonId}/reject")
	SalonView reject(@CurrentUserId UUID adminId, @PathVariable UUID salonId, @Valid @RequestBody NoteRequest request) {
		return admin.reject(salonId, adminId, request.note().strip());
	}

	@PostMapping("/{salonId}/suspend")
	SalonView suspend(@CurrentUserId UUID adminId, @PathVariable UUID salonId,
			@Valid @RequestBody NoteRequest request) {
		return admin.suspend(salonId, adminId, request.note().strip());
	}

	@PostMapping("/{salonId}/reactivate")
	SalonView reactivate(@CurrentUserId UUID adminId, @PathVariable UUID salonId) {
		return admin.reactivate(salonId, adminId);
	}

	/** The reason shown to the salon owner. */
	record NoteRequest(@NotBlank @Size(max = 1000) String note) {
	}

}
