package com.salon.identity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.salon.IntegrationTest;
import com.salon.RecordingMessagingConfiguration.RecordingEmailGateway;
import com.salon.RecordingMessagingConfiguration.RecordingSmsGateway;
import com.salon.audit.domain.AuditLogEntry;
import com.salon.audit.domain.AuditLogEntryRepository;
import com.salon.identity.domain.PlatformRole;
import com.salon.identity.domain.UserAccountRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class IdentityApiIntegrationTests {

	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	MockMvc mvc;

	@Autowired
	RecordingEmailGateway emails;

	@Autowired
	RecordingSmsGateway sms;

	@Autowired
	UserAccountRepository users;

	@Autowired
	AuditLogEntryRepository auditLog;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Nested
	class Registration {

		@Test
		void registerVerifyEmailLoginAndReadProfile() throws Exception {
			String email = uniqueEmail();

			register(email, PASSWORD).andExpect(status().isAccepted());
			login(email, PASSWORD).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

			verifyEmail(email, emails.lastCodeSentTo(email)).andExpect(status().isNoContent());
			String accessToken = JsonPath.read(login(email, PASSWORD).andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.accessToken");

			mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.emailVerified").value(true))
				.andExpect(jsonPath("$.phoneVerified").value(false))
				.andExpect(jsonPath("$.displayName").value("Test User"));

			UUID userId = users.findByEmail(email).orElseThrow().getId();
			assertThat(auditLog.findByActorUserIdOrderByOccurredAtAsc(userId)).extracting(AuditLogEntry::getAction)
				.contains("identity.user.registered", "identity.email.verified", "identity.login.succeeded");
		}

		@Test
		void emailIsNormalizedSoCaseDoesNotMatter() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email.toUpperCase(Locale.ROOT), PASSWORD);

			login(email, PASSWORD).andExpect(status().isOk());
		}

		@Test
		void registeringAnExistingEmailLooksIdenticalAndKeepsThePassword() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);

			register(email, "another-password-123").andExpect(status().isAccepted());

			login(email, PASSWORD).andExpect(status().isOk());
			login(email, "another-password-123").andExpect(status().isUnauthorized());
			assertThat(emails.sentTo(email).getLast().subject()).isEqualTo("Your Salon account");
		}

		@Test
		void wrongVerificationCodeIsRejected() throws Exception {
			String email = uniqueEmail();
			register(email, PASSWORD);
			String code = emails.lastCodeSentTo(email);
			String wrongCode = code.equals("000000") ? "111111" : "000000";

			verifyEmail(email, wrongCode).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_CODE"));
			verifyEmail(email, code).andExpect(status().isNoContent());
		}

		@Test
		void invalidRequestReturnsFieldErrors() throws Exception {
			mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email": "not-an-email", "password": "short", "displayName": ""}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("email", "password", "displayName")));
		}

	}

	@Nested
	class Login {

		@Test
		void unknownEmailAndWrongPasswordGiveTheSameError() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);

			login(email, "wrong-password-1").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
			login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}

		@Test
		void accountIsTemporarilyLockedAfterRepeatedFailures() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);

			for (int i = 0; i < 5; i++) {
				login(email, "wrong-password-" + i).andExpect(status().isUnauthorized());
			}

			login(email, PASSWORD).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("LOGIN_TEMPORARILY_LOCKED"));
		}

	}

	@Nested
	class Tokens {

		@Test
		void meRequiresAValidAccessToken() throws Exception {
			mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
			mvc.perform(get("/api/v1/me").header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized());
		}

		@Test
		void refreshRotatesTheRefreshToken() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String first = refreshTokenOf(login(email, PASSWORD));

			String second = refreshTokenOf(refresh(first).andExpect(status().isOk()));

			assertThat(second).isNotEqualTo(first);
			refresh(second).andExpect(status().isOk());
		}

		@Test
		void reusingARotatedRefreshTokenRevokesTheWholeSession() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String stolen = refreshTokenOf(login(email, PASSWORD));
			String current = refreshTokenOf(refresh(stolen));

			refresh(stolen).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

			refresh(current).andExpect(status().isUnauthorized());
		}

		@Test
		void logoutRevokesTheRefreshToken() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String refreshToken = refreshTokenOf(login(email, PASSWORD));

			mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
				.content(json("refreshToken", refreshToken))).andExpect(status().isNoContent());

			refresh(refreshToken).andExpect(status().isUnauthorized());
		}

		@Test
		void adminAreaRequiresThePlatformAdminRole() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String userToken = accessTokenOf(login(email, PASSWORD));

			mvc.perform(get("/api/v1/admin/ping").header("Authorization", "Bearer " + userToken))
				.andExpect(status().isForbidden());

			transactionTemplate.executeWithoutResult(status -> users.findByEmail(email)
				.orElseThrow()
				.grantPlatformRole(PlatformRole.PLATFORM_ADMIN, Instant.now()));
			String adminToken = accessTokenOf(login(email, PASSWORD));

			// Authorized; 404 because no admin endpoints exist yet.
			mvc.perform(get("/api/v1/admin/ping").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isNotFound());
			mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + adminToken))
				.andExpect(jsonPath("$.platformRoles", hasItem("PLATFORM_ADMIN")));
		}

	}

	@Nested
	class PasswordReset {

		@Test
		void resetChangesThePasswordAndEndsExistingSessions() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String oldSession = refreshTokenOf(login(email, PASSWORD));

			mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
				.content(json("email", email))).andExpect(status().isAccepted());
			String code = emails.lastCodeSentTo(email);
			mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
					{"email": "%s", "code": "%s", "newPassword": "a-brand-new-password"}
					""".formatted(email, code))).andExpect(status().isNoContent());

			login(email, PASSWORD).andExpect(status().isUnauthorized());
			login(email, "a-brand-new-password").andExpect(status().isOk());
			refresh(oldSession).andExpect(status().isUnauthorized());
		}

		@Test
		void forgotPasswordForUnknownEmailLooksTheSame() throws Exception {
			String email = uniqueEmail();

			mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
				.content(json("email", email))).andExpect(status().isAccepted());

			assertThat(emails.sentTo(email)).isEmpty();
		}

	}

	@Nested
	class PhoneVerification {

		@Test
		void verifiesATurkishMobileNumberEnteredInLocalFormat() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String token = accessTokenOf(login(email, PASSWORD));
			String phone = uniqueTurkishMobile();

			requestPhoneCode(token, "0" + phone.substring(3)).andExpect(status().isAccepted());
			confirmPhone(token, sms.lastCodeSentTo(phone)).andExpect(status().isNoContent());

			mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
				.andExpect(jsonPath("$.phone").value(phone))
				.andExpect(jsonPath("$.phoneVerified").value(true));
		}

		@Test
		void rejectsNumbersThatAreNotTurkishMobiles() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String token = accessTokenOf(login(email, PASSWORD));

			requestPhoneCode(token, "0212 123 45 67").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"));
		}

		@Test
		void aPhoneNumberCanBelongToOneAccountOnly() throws Exception {
			String phone = uniqueTurkishMobile();
			String first = uniqueEmail();
			registerAndVerify(first, PASSWORD);
			String firstToken = accessTokenOf(login(first, PASSWORD));
			requestPhoneCode(firstToken, phone);
			confirmPhone(firstToken, sms.lastCodeSentTo(phone)).andExpect(status().isNoContent());

			String second = uniqueEmail();
			registerAndVerify(second, PASSWORD);
			String secondToken = accessTokenOf(login(second, PASSWORD));

			requestPhoneCode(secondToken, phone).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PHONE_ALREADY_IN_USE"));
		}

		@Test
		void limitsHowManyCodesCanBeSentToOneNumberPerHour() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String token = accessTokenOf(login(email, PASSWORD));
			String phone = uniqueTurkishMobile();

			for (int i = 0; i < 5; i++) {
				requestPhoneCode(token, phone).andExpect(status().isAccepted());
			}

			requestPhoneCode(token, phone).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("VERIFICATION_RATE_LIMITED"));
			assertThat(sms.sentTo(phone)).hasSize(5);
		}

		@Test
		void onlyTheLatestCodeIsValid() throws Exception {
			String email = uniqueEmail();
			registerAndVerify(email, PASSWORD);
			String token = accessTokenOf(login(email, PASSWORD));
			String phone = uniqueTurkishMobile();

			requestPhoneCode(token, phone);
			String firstCode = sms.lastCodeSentTo(phone);
			requestPhoneCode(token, phone);
			String secondCode = sms.lastCodeSentTo(phone);

			if (!firstCode.equals(secondCode)) {
				confirmPhone(token, firstCode).andExpect(status().isBadRequest());
			}
			confirmPhone(token, secondCode).andExpect(status().isNoContent());
			mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
				.andExpect(jsonPath("$.phoneVerified").value(true))
				.andExpect(jsonPath("$.platformRoles", hasSize(0)));
		}

	}

	private ResultActions register(String email, String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s", "displayName": "Test User"}
				""".formatted(email, password)));
	}

	private ResultActions verifyEmail(String email, String code) throws Exception {
		return mvc.perform(post("/api/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "code": "%s"}
				""".formatted(email, code)));
	}

	private void registerAndVerify(String email, String password) throws Exception {
		register(email, password).andExpect(status().isAccepted());
		String normalized = email.toLowerCase(Locale.ROOT);
		verifyEmail(normalized, emails.lastCodeSentTo(normalized)).andExpect(status().isNoContent());
	}

	private ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email": "%s", "password": "%s"}
				""".formatted(email, password)));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content(json("refreshToken", refreshToken)));
	}

	private ResultActions requestPhoneCode(String accessToken, String phone) throws Exception {
		return mvc.perform(post("/api/v1/me/phone").header("Authorization", "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json("phone", phone)));
	}

	private ResultActions confirmPhone(String accessToken, String code) throws Exception {
		return mvc.perform(post("/api/v1/me/phone/verify").header("Authorization", "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json("code", code)));
	}

	private static String accessTokenOf(ResultActions login) throws Exception {
		return JsonPath.read(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.accessToken");
	}

	private static String refreshTokenOf(ResultActions login) throws Exception {
		return JsonPath.read(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");
	}

	private static String json(String field, String value) {
		return "{\"%s\": \"%s\"}".formatted(field, value);
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	/** A random +905XXXXXXXXX number, so tests never collide on the one-account-per-phone rule. */
	private static String uniqueTurkishMobile() {
		long suffix = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000L);
		return "+905" + String.format("%09d", suffix);
	}

}
