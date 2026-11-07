package com.salon.booking.web;

import java.util.UUID;

import com.salon.booking.application.BookingViews.ClientView;
import com.salon.booking.application.SalonClientService;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A salon's clients (ADR-0013 decisions 35 and 36). Customers who book online become clients
 * automatically; walk-ins and phone bookings are added here.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/clients")
class SalonClientsController {

	private final SalonClientService clients;

	SalonClientsController(SalonClientService clients) {
		this.clients = clients;
	}

	@GetMapping
	Page<ClientView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return clients.list(salonId, userId, pageable);
	}

	@GetMapping("/{clientId}")
	ClientView get(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID clientId) {
		return clients.get(salonId, clientId, userId);
	}

	/** A walk-in or phone-booking client; the phone (Turkish mobile) is optional. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ClientView create(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@Valid @RequestBody ClientRequest request) {
		return clients.create(salonId, userId, request.displayName().strip(), request.phone());
	}

	record ClientRequest(@NotBlank @Size(max = 100) String displayName, @Size(max = 30) String phone) {
	}

}
