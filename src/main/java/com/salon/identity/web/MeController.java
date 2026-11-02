package com.salon.identity.web;

import java.util.UUID;

import com.salon.identity.application.ProfileService;
import com.salon.identity.application.UserProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
	UserProfile me(@AuthenticationPrincipal Jwt jwt) {
		return profiles.get(userId(jwt));
	}

	@PostMapping("/phone")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void requestPhoneVerification(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PhoneRequest request) {
		profiles.requestPhoneVerification(userId(jwt), request.phone());
	}

	@PostMapping("/phone/verify")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void confirmPhone(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest request) {
		profiles.confirmPhone(userId(jwt), request.code());
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	record PhoneRequest(@NotBlank @Size(max = 32) String phone) {
	}

	record CodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
	}

}
