package com.salon.salons;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static com.salon.TestApi.branchJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation matrix (ADR-0002): every salon management endpoint is called by each role of
 * salon A, by the owner of another salon, and anonymously.
 * <p>
 * Outsiders must get 404 (salon ids cannot be probed), members without permission 403,
 * anonymous callers 401. Matrix cases share one fixture, so every case must be idempotent.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationIntegrationTests {

	enum Caller {

		OWNER(StaffRole.OWNER), MANAGER(StaffRole.MANAGER), RECEPTIONIST(StaffRole.RECEPTIONIST),
		STYLIST(StaffRole.STAFF), OUTSIDER(null), ANONYMOUS(null);

		final StaffRole role;

		Caller(StaffRole role) {
			this.role = role;
		}

	}

	/**
	 * Path placeholders: {salon}, {branch}, {staff} refer to salon A's resources.
	 */
	record Endpoint(HttpMethod method, String path, String body, int owner, int manager, int receptionist,
			int stylist, int outsider, int anonymous, boolean idempotencyKey) {

		Endpoint(HttpMethod method, String path, String body, int owner, int manager, int receptionist, int stylist,
				int outsider, int anonymous) {
			this(method, path, body, owner, manager, receptionist, stylist, outsider, anonymous, false);
		}

		int expectedFor(Caller caller) {
			return switch (caller) {
				case OWNER -> owner;
				case MANAGER -> manager;
				case RECEPTIONIST -> receptionist;
				case STYLIST -> stylist;
				case OUTSIDER -> outsider;
				case ANONYMOUS -> anonymous;
			};
		}

		@Override
		public String toString() {
			return method + " " + path;
		}

	}

	private static final String STAFF_JSON = """
			{"displayName": "New Stylist", "role": "STAFF", "bookable": true}
			""";

	/** Keeps {staff} assigned to {branch}, which working hours there require. */
	private static final String STAFF_IN_BRANCH_JSON = """
			{"displayName": "New Stylist", "role": "STAFF", "bookable": true, "branchIds": ["{branch}"]}
			""";

	private static final String HOURS_JSON = """
			{"periods": [{"effectiveFrom": "2026-01-01",
			              "intervals": [{"dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "17:00"}]}]}
			""";

	private static final String OPENING_HOURS_JSON = """
			{"intervals": [{"dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "18:00"}]}
			""";

	private static final String TIME_OFF_JSON = """
			{"startAt": "2030-01-07T09:00:00+03:00", "endAt": "2030-01-07T12:00:00+03:00"}
			""";

	@Autowired
	TestApi api;

	private UUID salonA;

	private UUID branchA;

	private UUID unlinkedStaffA;

	private UUID serviceA;

	private UUID sectionA;

	/** Offered at {branch} for the whole class (never removed by a matrix case). */
	private UUID offeredA;

	/** An id that exists nowhere: appointment and client endpoints by id. */
	private final UUID unknown = UUID.randomUUID();

	private String ownerToken;

	private UUID salonB;

	private Map<Caller, String> tokens;

	@BeforeAll
	void createTwoSalons() throws Exception {
		TestUser owner = api.signUp("Owner A");
		TestUser manager = api.signUp("Manager A");
		TestUser receptionist = api.signUp("Receptionist A");
		TestUser stylist = api.signUp("Stylist A");
		TestUser outsider = api.signUp("Owner B");

		salonA = api.createSalon(owner, "Tenant A");
		branchA = api.createBranch(owner, salonA);
		api.addMember(owner, salonA, "MANAGER", manager);
		api.addMember(owner, salonA, "RECEPTIONIST", receptionist);
		api.addMember(owner, salonA, "STAFF", stylist);
		unlinkedStaffA = UUID.fromString(TestApi.read(api.post(owner.accessToken(), "/api/v1/salons/" + salonA
				+ "/staff", STAFF_IN_BRANCH_JSON.replace("{branch}", branchA.toString())), "$.id"));
		serviceA = api.createService(owner, salonA, "Cut", 30);
		sectionA = UUID.fromString(TestApi.read(api.post(owner.accessToken(), "/api/v1/salons/" + salonA
				+ "/service-sections", "{\"name\": \"Saç\"}"), "$.id"));
		offeredA = api.createService(owner, salonA, "Availability Cut", 30);
		api.put(owner.accessToken(), "/api/v1/salons/" + salonA + "/services/" + offeredA + "/branches/" + branchA, "{}");
		ownerToken = owner.accessToken();

		salonB = api.createSalon(outsider, "Tenant B");
		api.createBranch(outsider, salonB);

		tokens = Map.of(Caller.OWNER, owner.accessToken(), Caller.MANAGER, manager.accessToken(),
				Caller.RECEPTIONIST, receptionist.accessToken(), Caller.STYLIST, stylist.accessToken(),
				Caller.OUTSIDER, outsider.accessToken());
	}

	static Stream<Endpoint> endpoints() {
		return Stream.of(
				new Endpoint(HttpMethod.GET, "/salons/{salon}", null, 200, 200, 200, 200, 404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}", "{\"name\": \"Tenant A\"}", 200, 200, 403, 403, 404,
						401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/branches", null, 200, 200, 200, 200, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/branches", branchJson("Extra", true), 201, 201, 403,
						403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/branches/{branch}", null, 200, 200, 200, 200, 404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/branches/{branch}", branchJson("Main", true), 200, 200,
						403, 403, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/branches/{branch}/activate", null, 200, 200, 403, 403,
						404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/staff", null, 200, 200, 200, 200, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/staff", STAFF_JSON, 201, 201, 403, 403, 404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/staff/{staff}", STAFF_IN_BRANCH_JSON, 200, 200, 403,
						403, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/staff/{staff}/invitations",
						"{\"email\": \"new-stylist@example.com\"}", 202, 202, 403, 403, 404, 401),
				new Endpoint(HttpMethod.DELETE, "/salons/{salon}/staff/{staff}/invitations", null, 204, 204, 403, 403,
						404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/staff/{staff}/reactivate", null, 200, 200, 403, 403,
						404, 401),
				// Catalog (CATALOG_MANAGE to change, any member to read)
				new Endpoint(HttpMethod.GET, "/salons/{salon}/services", null, 200, 200, 200, 200, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/services", TestApi.serviceJson("Extra", 30), 201, 201,
						403, 403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/services/{service}", null, 200, 200, 200, 200, 404,
						401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/services/{service}", TestApi.serviceJson("Cut", 30), 200,
						200, 403, 403, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/services/{service}/archive", null, 200, 200, 403, 403,
						404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/services/{service}/restore", null, 200, 200, 403, 403,
						404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/services/{service}/branches/{branch}", "{}", 200, 200,
						403, 403, 404, 401),
				new Endpoint(HttpMethod.DELETE, "/salons/{salon}/services/{service}/branches/{branch}", null, 200,
						200, 403, 403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/service-sections", null, 200, 200, 200, 200, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/service-sections", "{\"name\": \"Extra\"}", 201, 201,
						403, 403, 404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/service-sections/{section}", "{\"name\": \"Saç\"}", 200,
						200, 403, 403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/staff/{staff}/skills", null, 200, 200, 200, 200, 404,
						401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/staff/{staff}/skills",
						"{\"skills\": [{\"serviceId\": \"{service}\"}]}", 200, 200, 403, 403, 404, 401),
				// Branch calendar (BRANCH_MANAGE to change, any member to read)
				new Endpoint(HttpMethod.GET, "/salons/{salon}/branches/{branch}/opening-hours", null, 200, 200, 200,
						200, 404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/branches/{branch}/opening-hours", OPENING_HOURS_JSON,
						200, 200, 403, 403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/branches/{branch}/special-days", null, 200, 200, 200,
						200, 404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/branches/{branch}/special-days/2030-01-01",
						"{\"intervals\": []}", 200, 200, 403, 403, 404, 401),
				new Endpoint(HttpMethod.DELETE, "/salons/{salon}/branches/{branch}/special-days/2030-01-01", null, 204,
						204, 403, 403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/branches/{branch}/closures", null, 200, 200, 200, 200,
						404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/branches/{branch}/closures",
						"{\"startDate\": \"2030-01-01\", \"endDate\": \"2030-01-02\"}", 201, 201, 403, 403, 404, 401),
				// Staff schedules ({staff} is not the stylist: SCHEDULE_MANAGE_OWN does not apply)
				new Endpoint(HttpMethod.GET, "/salons/{salon}/staff/{staff}/working-hours", null, 200, 200, 200, 200,
						404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/staff/{staff}/branches/{branch}/working-hours",
						HOURS_JSON, 200, 200, 403, 403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/staff/{staff}/time-off", null, 200, 200, 403, 403, 404,
						401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/staff/{staff}/time-off", TIME_OFF_JSON, 201, 201, 403,
						403, 404, 401),
				// Booking (ADR-0013): appointment reads per ADR-0010, clients need CLIENT_VIEW
				new Endpoint(HttpMethod.GET, "/salons/{salon}/appointments", null, 200, 200, 200, 200, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments", """
						{"branchId": "{branch}", "clientId": "{unknown}", "serviceIds": ["{offered}"],
						 "start": "2030-01-07T09:00:00Z"}
						""", 404, 404, 403, 403, 404, 401, true),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/appointments/{unknown}", null, 404, 404, 404, 404, 404,
						401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments/{unknown}/confirm", null, 404, 404, 404, 404,
						404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments/{unknown}/reject", null, 404, 404, 404, 404,
						404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments/{unknown}/cancel",
						"{\"reason\": \"Matrix\"}", 404, 404, 404, 404, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments/{unknown}/reschedule",
						"{\"start\": \"2030-01-07T09:00:00Z\"}", 404, 404, 404, 404, 404, 401, true),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments/{unknown}/complete", null, 404, 404, 404,
						404, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments/{unknown}/no-show", null, 404, 404, 404, 404,
						404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/appointments/{unknown}/resolve-attention", null, 404, 404,
						404, 404, 404, 401),
				new Endpoint(HttpMethod.PUT, "/salons/{salon}/appointments/{unknown}/internal-note",
						"{\"note\": \"Matrix\"}", 404, 404, 404, 404, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/clients", null, 200, 200, 200, 403, 404, 401),
				new Endpoint(HttpMethod.GET, "/salons/{salon}/clients/{unknown}", null, 404, 404, 404, 403, 404, 401),
				new Endpoint(HttpMethod.POST, "/salons/{salon}/clients", "{\"displayName\": \"Matrix Client\"}", 201,
						201, 201, 201, 404, 401),
				// Salon-side availability (decision 20/37): unassigned receptionists and stylists without a
				// staffId are outside their scope
				new Endpoint(HttpMethod.GET,
						"/salons/{salon}/branches/{branch}/availability?serviceIds={offered}&from=2030-01-07&to=2030-01-07",
						null, 200, 200, 403, 403, 404, 401));
	}

	/** Endpoints that delete one resource by id: a fresh resource per call, created by the owner. */
	enum DeleteEndpoint {

		SECTION("/salons/{salon}/service-sections", "{\"name\": \"Temporary\"}", "/salons/{salon}/service-sections/"),
		CLOSURE("/salons/{salon}/branches/{branch}/closures",
				"{\"startDate\": \"2030-02-01\", \"endDate\": \"2030-02-01\"}",
				"/salons/{salon}/branches/{branch}/closures/"),
		TIME_OFF("/salons/{salon}/staff/{staff}/time-off", TIME_OFF_JSON, "/salons/{salon}/staff/{staff}/time-off/");

		final String createPath;

		final String createBody;

		final String deletePathPrefix;

		DeleteEndpoint(String createPath, String createBody, String deletePathPrefix) {
			this.createPath = createPath;
			this.createBody = createBody;
			this.deletePathPrefix = deletePathPrefix;
		}

	}

	Stream<Arguments> deletesByCaller() {
		return Stream.of(DeleteEndpoint.values())
			.flatMap(endpoint -> Stream.of(Caller.values())
				.map(caller -> Arguments.argumentSet("DELETE " + endpoint + " as " + caller, endpoint, caller)));
	}

	@ParameterizedTest
	@MethodSource("deletesByCaller")
	void deletesAreLimitedToMembersWithPermission(DeleteEndpoint endpoint, Caller caller) throws Exception {
		String id = TestApi.read(api.post(ownerToken, resolve(endpoint.createPath), resolve(endpoint.createBody))
			.andExpect(status().isCreated()), "$.id");
		int expected = switch (caller) {
			case OWNER, MANAGER -> 204;
			case RECEPTIONIST, STYLIST -> 403;
			case OUTSIDER -> 404;
			case ANONYMOUS -> 401;
		};

		api.delete(tokens.get(caller), resolve(endpoint.deletePathPrefix) + id).andExpect(status().is(expected));
	}

	Stream<Arguments> endpointsByCaller() {
		return endpoints().flatMap(endpoint -> Stream.of(Caller.values())
			.map(caller -> Arguments.argumentSet(endpoint + " as " + caller, endpoint, caller)));
	}

	@ParameterizedTest
	@MethodSource("endpointsByCaller")
	void accessIsLimitedToMembersWithPermission(Endpoint endpoint, Caller caller) throws Exception {
		(endpoint.idempotencyKey()
				? api.performWithKey(endpoint.method(), tokens.get(caller), resolve(endpoint.path()),
						resolve(endpoint.body()), UUID.randomUUID().toString())
				: api.perform(endpoint.method(), tokens.get(caller), resolve(endpoint.path()), resolve(endpoint.body())))
			.andExpect(status().is(endpoint.expectedFor(caller)));
	}

	@Test
	void onlyMembersWithSalonManageCanSubmitForReview() throws Exception {
		String submit = resolve("/salons/{salon}/submit-for-review");
		api.post(tokens.get(Caller.RECEPTIONIST), submit, null).andExpect(status().isForbidden());
		api.post(tokens.get(Caller.STYLIST), submit, null).andExpect(status().isForbidden());
		api.post(tokens.get(Caller.OUTSIDER), submit, null).andExpect(status().isNotFound());
		api.post(null, submit, null).andExpect(status().isUnauthorized());
	}

	/** The permissions clients receive must be exactly those the server enforces. */
	@ParameterizedTest
	@EnumSource(value = Caller.class, names = { "OWNER", "MANAGER", "RECEPTIONIST", "STYLIST" })
	void membershipListsExactlyTheRolePermissions(Caller caller) throws Exception {
		String json = api.get(tokens.get(caller), "/api/v1/me/salons")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		List<String> roles = JsonPath.read(json, "$[?(@.salonId == '%s')].role".formatted(salonA));
		List<List<String>> permissions = JsonPath.read(json, "$[?(@.salonId == '%s')].permissions".formatted(salonA));

		assertThat(roles).containsExactly(caller.role.name());
		assertThat(permissions.getFirst()).containsExactlyInAnyOrderElementsOf(
				caller.role.permissions().stream().map(Enum::name).collect(Collectors.toSet()));
	}

	@Test
	void resourceIdsOfAnotherSalonAreNotFoundThroughTheOutsidersOwnSalon() throws Exception {
		String outsider = tokens.get(Caller.OUTSIDER);
		String ownSalon = "/api/v1/salons/" + salonB;

		api.get(outsider, ownSalon + "/branches/" + branchA)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
		api.put(outsider, ownSalon + "/branches/" + branchA, branchJson("Hijack", true))
			.andExpect(status().isNotFound());
		api.put(outsider, ownSalon + "/staff/" + unlinkedStaffA, STAFF_JSON)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
		api.post(outsider, ownSalon + "/staff/" + unlinkedStaffA + "/deactivate", null)
			.andExpect(status().isNotFound());
	}

	@Test
	void catalogAndScheduleIdsOfAnotherSalonAreNotFoundThroughTheOutsidersOwnSalon() throws Exception {
		String outsider = tokens.get(Caller.OUTSIDER);
		String ownSalon = "/api/v1/salons/" + salonB;

		api.get(outsider, ownSalon + "/services/" + serviceA)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
		api.put(outsider, ownSalon + "/services/" + serviceA, TestApi.serviceJson("Hijack", 30))
			.andExpect(status().isNotFound());
		api.put(outsider, ownSalon + "/service-sections/" + sectionA, "{\"name\": \"Hijack\"}")
			.andExpect(jsonPath("$.code").value("SERVICE_SECTION_NOT_FOUND"));
		api.put(outsider, ownSalon + "/branches/" + branchA + "/opening-hours", OPENING_HOURS_JSON)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
		api.get(outsider, ownSalon + "/branches/" + branchA + "/closures")
			.andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
		api.put(outsider, ownSalon + "/staff/" + unlinkedStaffA + "/skills", "{\"skills\": []}")
			.andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
		api.get(outsider, ownSalon + "/staff/" + unlinkedStaffA + "/working-hours")
			.andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
		api.get(outsider, ownSalon + "/staff/" + unlinkedStaffA + "/time-off")
			.andExpect(jsonPath("$.code").value("STAFF_NOT_FOUND"));
		api.post(outsider, ownSalon + "/staff/" + unlinkedStaffA + "/time-off", TIME_OFF_JSON)
			.andExpect(status().isNotFound());
	}

	@Test
	void nonMembersGetTheSameAnswerForExistingAndUnknownSalons() throws Exception {
		String outsider = tokens.get(Caller.OUTSIDER);

		api.get(outsider, "/api/v1/salons/" + salonA)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SALON_NOT_FOUND"));
		api.get(outsider, "/api/v1/salons/" + UUID.randomUUID())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SALON_NOT_FOUND"));
		api.put(outsider, "/api/v1/salons/" + UUID.randomUUID() + "/staff/" + unlinkedStaffA, STAFF_JSON)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SALON_NOT_FOUND"));
	}

	/** Separate salon: suspending salon A would break the shared matrix fixture. */
	@Test
	void outsidersCannotLearnThatASalonIsSuspended() throws Exception {
		TestUser owner = api.signUp("Suspended Owner");
		TestUser admin = api.makePlatformAdmin(api.signUp("Admin"));
		UUID salonId = api.createSalon(owner, "Suspended For Outsiders");
		api.createBranch(owner, salonId);
		String staffId = TestApi.read(api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff",
				STAFF_JSON), "$.id");
		api.post(owner.accessToken(), "/api/v1/salons/" + salonId + "/submit-for-review", null)
			.andExpect(status().isOk());
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/approve", null)
			.andExpect(status().isOk());
		api.post(admin.accessToken(), "/api/v1/admin/salons/" + salonId + "/suspend", "{\"note\": \"Check\"}")
			.andExpect(status().isOk());
		String outsider = tokens.get(Caller.OUTSIDER);

		api.put(outsider, "/api/v1/salons/" + salonId + "/staff/" + staffId, STAFF_JSON)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SALON_NOT_FOUND"));
		api.post(outsider, "/api/v1/salons/" + salonId + "/staff", STAFF_JSON)
			.andExpect(jsonPath("$.code").value("SALON_NOT_FOUND"));
		api.put(outsider, "/api/v1/salons/" + salonId, "{\"name\": \"Taken Over\"}")
			.andExpect(jsonPath("$.code").value("SALON_NOT_FOUND"));
		// Members do learn it.
		api.put(owner.accessToken(), "/api/v1/salons/" + salonId + "/staff/" + staffId, STAFF_JSON)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALON_SUSPENDED"));
	}

	/** Replaces the placeholders; paths (starting with "/") also get the API prefix. */
	private String resolve(String pathOrBody) {
		if (pathOrBody == null) {
			return null;
		}
		String resolved = pathOrBody.replace("{salon}", salonA.toString())
			.replace("{branch}", branchA.toString())
			.replace("{staff}", unlinkedStaffA.toString())
			.replace("{service}", serviceA.toString())
			.replace("{section}", sectionA.toString())
			.replace("{offered}", offeredA.toString())
			.replace("{unknown}", unknown.toString());
		return resolved.startsWith("/") ? "/api/v1" + resolved : resolved;
	}

}
