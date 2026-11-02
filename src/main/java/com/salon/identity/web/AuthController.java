package com.salon.identity.web;

import com.salon.identity.application.AccountService;
import com.salon.identity.application.AuthTokens;
import com.salon.identity.application.AuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints. Endpoints that could reveal whether an email is registered
 * respond identically in all cases ({@code 202 Accepted}).
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final AccountService accounts;

	private final AuthenticationService authentication;

	AuthController(AccountService accounts, AuthenticationService authentication) {
		this.accounts = accounts;
		this.authentication = authentication;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void register(@Valid @RequestBody RegisterRequest request) {
		accounts.register(request.email(), request.password(), request.displayName());
	}

	@PostMapping("/email/verify")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void verifyEmail(@Valid @RequestBody VerifyCodeRequest request) {
		accounts.verifyEmail(request.email(), request.code());
	}

	@PostMapping("/email/resend")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void resendEmailVerification(@Valid @RequestBody EmailRequest request) {
		accounts.resendEmailVerification(request.email());
	}

	@PostMapping("/login")
	AuthTokens login(@Valid @RequestBody LoginRequest request) {
		return authentication.login(request.email(), request.password());
	}

	@PostMapping("/refresh")
	AuthTokens refresh(@Valid @RequestBody RefreshRequest request) {
		return authentication.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@Valid @RequestBody RefreshRequest request) {
		authentication.logout(request.refreshToken());
	}

	@PostMapping("/password/forgot")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void forgotPassword(@Valid @RequestBody EmailRequest request) {
		accounts.requestPasswordReset(request.email());
	}

	@PostMapping("/password/reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		accounts.resetPassword(request.email(), request.code(), request.newPassword());
	}

	record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 10, max = 72) String password, @NotBlank @Size(max = 100) String displayName) {
	}

	record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 256) String password) {
	}

	record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
	}

	record VerifyCodeRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Pattern(regexp = "\\d{6}") String code) {
	}

	record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
	}

	record ResetPasswordRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Pattern(regexp = "\\d{6}") String code, @NotBlank @Size(min = 10, max = 72) String newPassword) {
	}

}
