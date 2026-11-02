package com.salon.identity.web;

import java.util.UUID;

import com.salon.identity.application.ProfileService;
import com.salon.identity.application.UserProfile;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own account.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

	private final ProfileService profiles;

	MeController(ProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	UserProfile me(@CurrentUserId UUID userId) {
		return profiles.get(userId);
	}

	@PostMapping("/phone")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void requestPhoneVerification(@CurrentUserId UUID userId, @Valid @RequestBody PhoneRequest request) {
		profiles.requestPhoneVerification(userId, request.phone());
	}

	@PostMapping("/phone/verify")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void confirmPhone(@CurrentUserId UUID userId, @Valid @RequestBody CodeRequest request) {
		profiles.confirmPhone(userId, request.code());
	}

	record PhoneRequest(@NotBlank @Size(max = 32) String phone) {
	}

	record CodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
	}

}
