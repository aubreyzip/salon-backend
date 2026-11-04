package com.salon;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import com.salon.RecordingMessagingConfiguration.RecordingEmailGateway;
import com.salon.identity.domain.PlatformRole;
import com.salon.identity.domain.UserAccountRepository;
import com.salon.messaging.EmailMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the API like a client would: signs users up, creates salons and branches, and accepts
 * invitations. Use unique names per test; the database is shared by all integration tests.
 */
public class TestApi {

	public static final String PASSWORD = "correct-horse-battery";

	private static final Pattern INVITATION_TOKEN = Pattern.compile("invitation code: (\\S+)");

	private final MockMvc mvc;

	private final RecordingEmailGateway emails;

	private final UserAccountRepository users;

	private final TransactionTemplate transactionTemplate;

	TestApi(MockMvc mvc, RecordingEmailGateway emails, UserAccountRepository users,
			TransactionTemplate transactionTemplate) {
		this.mvc = mvc;
		this.emails = emails;
		this.users = users;
		this.transactionTemplate = transactionTemplate;
	}

	public record TestUser(UUID id, String email, String accessToken) {
	}

	/** Registers, verifies and signs in a new user with a unique email address. */
	public TestUser signUp(String displayName) throws Exception {
		return signUp(displayName, uniqueEmail());
	}

	public TestUser signUp(String displayName, String email) throws Exception {
		perform(HttpMethod.POST, null, "/api/v1/auth/register", """
				{"email": "%s", "password": "%s", "displayName": "%s"}
				""".formatted(email, PASSWORD, displayName)).andExpect(status().isAccepted());
		perform(HttpMethod.POST, null, "/api/v1/auth/email/verify", """
				{"email": "%s", "code": "%s"}
				""".formatted(email, emails.lastCodeSentTo(email))).andExpect(status().isNoContent());
		return signIn(email);
	}

	public static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	public TestUser signIn(String email) throws Exception {
		String token = read(perform(HttpMethod.POST, null, "/api/v1/auth/login", """
				{"email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD)).andExpect(status().isOk()), "$.accessToken");
		UUID id = UUID.fromString(read(get(token, "/api/v1/me").andExpect(status().isOk()), "$.id"));
		return new TestUser(id, email, token);
	}

	/** Grants {@code PLATFORM_ADMIN} directly in the database and signs in again to get the role claim. */
	public TestUser makePlatformAdmin(TestUser user) throws Exception {
		transactionTemplate.executeWithoutResult(status -> users.findByEmail(user.email().toLowerCase(Locale.ROOT))
			.orElseThrow()
			.grantPlatformRole(PlatformRole.PLATFORM_ADMIN, Instant.now()));
		return signIn(user.email());
	}

	public UUID createSalon(TestUser owner, String name) throws Exception {
		return UUID.fromString(read(post(owner.accessToken(), "/api/v1/salons", """
				{"name": "%s", "description": "Test salon"}
				""".formatted(name)).andExpect(status().isCreated()), "$.id"));
	}

	public UUID createBranch(TestUser user, UUID salonId) throws Exception {
		return UUID.fromString(read(post(user.accessToken(), "/api/v1/salons/" + salonId + "/branches",
				branchJson("Kadıköy Şubesi", true)).andExpect(status().isCreated()), "$.id"));
	}

	/**
	 * Submits the salon for review and approves it with a new platform admin, so it becomes
	 * {@code ACTIVE} (public). The salon needs an active branch with a location ({@link #createBranch}).
	 */
	public void approveSalon(TestUser owner, UUID salonId) throws Exception {
		TestUser admin = makePlatformAdmin(signUp("Admin"));
		post(owner.accessToken(), "/api/v1/salons/" + salonId + "/submit-for-review", null)
			.andExpect(status().isOk());
		post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/approve", null).andExpect(status().isOk());
	}

	/** Creates an online-bookable "haircut" category service without buffer. */
	public UUID createService(TestUser user, UUID salonId, String name, int durationMinutes) throws Exception {
		return UUID.fromString(read(post(user.accessToken(), "/api/v1/salons/" + salonId + "/services",
				serviceJson(name, durationMinutes)).andExpect(status().isCreated()), "$.id"));
	}

	public static String serviceJson(String name, int durationMinutes) {
		return """
				{"categoryId": 1, "name": "%s", "durationMinutes": %d, "price": 450.00}
				""".formatted(name, durationMinutes);
	}

	/** A valid branch in İstanbul, optionally without map coordinates. */
	public static String branchJson(String name, boolean withLocation) {
		String location = withLocation ? "\"latitude\": 40.9903, \"longitude\": 29.0290," : "";
		return """
				{"name": "%s", "phone": "0216 123 45 67",
				 "address": {"provinceId": 34, "district": "Kadıköy", "neighbourhood": "Caferağa",
				             "addressLine": "Moda Cad. No: 1", "postalCode": "34710"},
				 %s "timeZone": "Europe/Istanbul"}
				""".formatted(name, location);
	}

	/**
	 * Creates a staff member with the given role, invites {@code invitee} and accepts the invitation
	 * as {@code invitee}.
	 * @return the staff member id
	 */
	public UUID addMember(TestUser actor, UUID salonId, String role, TestUser invitee) throws Exception {
		UUID staffId = UUID.fromString(read(post(actor.accessToken(), "/api/v1/salons/" + salonId + "/staff", """
				{"displayName": "Member", "role": "%s", "bookable": true, "inviteEmail": "%s"}
				""".formatted(role, invitee.email())).andExpect(status().isCreated()), "$.id"));
		post(invitee.accessToken(), "/api/v1/me/invitations/accept", """
				{"token": "%s"}
				""".formatted(lastInvitationTokenSentTo(invitee.email()))).andExpect(status().isOk());
		return staffId;
	}

	public String lastInvitationTokenSentTo(String email) {
		List<EmailMessage> messages = emails.sentTo(email.toLowerCase(Locale.ROOT));
		for (int i = messages.size() - 1; i >= 0; i--) {
			Matcher matcher = INVITATION_TOKEN.matcher(messages.get(i).body());
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		throw new AssertionError("No invitation sent to " + email);
	}

	public ResultActions get(String accessToken, String path) throws Exception {
		return perform(HttpMethod.GET, accessToken, path, null);
	}

	public ResultActions post(String accessToken, String path, String json) throws Exception {
		return perform(HttpMethod.POST, accessToken, path, json);
	}

	public ResultActions put(String accessToken, String path, String json) throws Exception {
		return perform(HttpMethod.PUT, accessToken, path, json);
	}

	public ResultActions delete(String accessToken, String path) throws Exception {
		return perform(HttpMethod.DELETE, accessToken, path, null);
	}

	public ResultActions perform(HttpMethod method, String accessToken, String path, String json) throws Exception {
		MockHttpServletRequestBuilder request = request(method, path);
		if (accessToken != null) {
			request.header("Authorization", "Bearer " + accessToken);
		}
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	public static String read(ResultActions result, String jsonPath) throws Exception {
		Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
		return String.valueOf(value);
	}

}
