package com.salon.salons.web;

import java.util.List;
import java.util.UUID;

import com.salon.salons.application.InvitationService;
import com.salon.salons.application.MySalonView;
import com.salon.salons.application.SalonService;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's salon memberships.
 */
@RestController
@RequestMapping("/api/v1/me")
class MySalonsController {

	private final SalonService salons;

	private final InvitationService invitations;

	MySalonsController(SalonService salons, InvitationService invitations) {
		this.salons = salons;
		this.invitations = invitations;
	}

	@GetMapping("/salons")
	List<MySalonView> mySalons(@CurrentUserId UUID userId) {
		return salons.mySalons(userId);
	}

	@PostMapping("/invitations/accept")
	MySalonView acceptInvitation(@CurrentUserId UUID userId, @Valid @RequestBody AcceptInvitationRequest request) {
		return invitations.accept(userId, request.token().strip());
	}

	record AcceptInvitationRequest(@NotBlank @Size(max = 100) String token) {
	}

}
