package com.salon;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salon.TestApi.TestUser;
import org.springframework.test.web.servlet.ResultActions;

import static com.salon.TestApi.read;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test fixture for booking: an approved (public) salon with one branch in İstanbul, open 09:00–18:00
 * every day, with the default booking policy (automatic confirmation, 15-minute grid, 60 minutes
 * notice, 60 days advance, 120 minutes cancellation cutoff, 120 minutes confirmation timeout).
 * Services and staff are added per test. Use days a few days ahead ({@link #DAY}).
 */
public final class BookableSalon {

	public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

	/** A day far enough ahead for every default policy. */
	public static final LocalDate DAY = LocalDate.now(ZONE).plusDays(3);

	private final TestApi api;

	public final TestUser owner;

	public final UUID salonId;

	public final UUID branchId;

	/** {@code /api/v1/salons/{salonId}} */
	public final String path;

	private BookableSalon(TestApi api, TestUser owner, UUID salonId, UUID branchId) {
		this.api = api;
		this.owner = owner;
		this.salonId = salonId;
		this.branchId = branchId;
		this.path = "/api/v1/salons/" + salonId;
	}

	public static BookableSalon create(TestApi api, String name) throws Exception {
		TestUser owner = api.signUp(name + " Owner");
		UUID salonId = api.createSalon(owner, name);
		UUID branchId = api.createBranch(owner, salonId);
		BookableSalon salon = new BookableSalon(api, owner, salonId, branchId);
		salon.openingHours(salon.branchId, "09:00", "18:00");
		api.approveSalon(owner, salonId);
		return salon;
	}

	/** A further branch with the same opening hours. */
	public UUID addBranch() throws Exception {
		UUID branch = api.createBranch(owner, salonId);
		openingHours(branch, "09:00", "18:00");
		return branch;
	}

	public void openingHours(UUID branch, String start, String end) throws Exception {
		api.put(owner.accessToken(), path + "/branches/" + branch + "/opening-hours",
				"{\"intervals\": " + everyDay(start, end) + "}")
			.andExpect(status().isOk());
	}

	/** Changes the branch's booking policy (ADR-0009, ADR-0013). */
	public void bookingPolicy(boolean autoConfirm, int minNoticeMinutes, int cancellationCutoffMinutes,
			int pendingTimeoutMinutes) throws Exception {
		String branch = TestApi.branchJson("Kadıköy Şubesi", true).strip();
		String json = branch.substring(0, branch.lastIndexOf('}')) + """
				, "bookingPolicy": {"autoConfirm": %s, "slotIntervalMinutes": 15, "minNoticeMinutes": %d,
				  "maxAdvanceDays": 60, "cancellationCutoffMinutes": %d, "pendingConfirmationTimeoutMinutes": %d}}
				""".formatted(autoConfirm, minNoticeMinutes, cancellationCutoffMinutes, pendingTimeoutMinutes);
		api.put(owner.accessToken(), path + "/branches/" + branchId, json).andExpect(status().isOk());
	}

	/** A service without buffer, offered online at the main branch. */
	public UUID service(String name, int minutes) throws Exception {
		return service(TestApi.serviceJson(name, minutes));
	}

	/** A service from JSON (see {@link TestApi#serviceJson}), offered at the main branch. */
	public UUID service(String json) throws Exception {
		UUID id = UUID.fromString(read(api.post(owner.accessToken(), path + "/services", json)
			.andExpect(status().isCreated()), "$.id"));
		offer(id, branchId);
		return id;
	}

	public void offer(UUID serviceId, UUID branch) throws Exception {
		api.put(owner.accessToken(), path + "/services/" + serviceId + "/branches/" + branch, "{}")
			.andExpect(status().isOk());
	}

	/**
	 * A bookable staff member (no account) at the main branch, skilled in the services and working
	 * {@code start}–{@code end} every day.
	 */
	public UUID staff(String name, String start, String end, UUID... serviceIds) throws Exception {
		UUID staffId = UUID.fromString(read(api.post(owner.accessToken(), path + "/staff", """
				{"displayName": "%s", "role": "STAFF", "bookable": true, "branchIds": ["%s"]}
				""".formatted(name, branchId)).andExpect(status().isCreated()), "$.id"));
		skills(staffId, serviceIds);
		workingHours(staffId, branchId, start, end);
		return staffId;
	}

	/**
	 * Adds {@code user} as a member with the role, assigned to the branches (for linked staff, e.g. a
	 * stylist who manages their own appointments).
	 */
	public UUID member(String role, TestUser user, UUID... branches) throws Exception {
		UUID staffId = api.addMember(owner, salonId, role, user);
		api.put(owner.accessToken(), path + "/staff/" + staffId, """
				{"displayName": "%s", "role": "%s", "bookable": true, "branchIds": [%s]}
				""".formatted(user.email(), role, Arrays.stream(branches)
			.map(id -> "\"" + id + "\"")
			.collect(Collectors.joining(",")))).andExpect(status().isOk());
		return staffId;
	}

	public void skills(UUID staffId, UUID... serviceIds) throws Exception {
		String skills = Arrays.stream(serviceIds)
			.map(id -> "{\"serviceId\": \"" + id + "\"}")
			.collect(Collectors.joining(","));
		api.put(owner.accessToken(), path + "/staff/" + staffId + "/skills", "{\"skills\": [" + skills + "]}")
			.andExpect(status().isOk());
	}

	public void workingHours(UUID staffId, UUID branch, String start, String end) throws Exception {
		api.put(owner.accessToken(), path + "/staff/" + staffId + "/branches/" + branch + "/working-hours",
				"{\"periods\": [{\"effectiveFrom\": \"2026-01-01\", \"intervals\": " + everyDay(start, end) + "}]}")
			.andExpect(status().isOk());
	}

	/** A customer booking at the main branch. {@code staffId} {@code null}: anyone available. */
	public ResultActions book(TestUser customer, UUID staffId, Instant start, UUID... serviceIds) throws Exception {
		return api.postWithKey(customer.accessToken(), "/api/v1/me/appointments",
				bookJson(branchId, staffId, start, serviceIds));
	}

	/** Books and returns the appointment id; the booking must succeed. */
	public UUID booked(TestUser customer, UUID staffId, Instant start, UUID... serviceIds) throws Exception {
		return UUID.fromString(read(book(customer, staffId, start, serviceIds).andExpect(status().isCreated()), "$.id"));
	}

	public static String bookJson(UUID branch, UUID staffId, Instant start, UUID... serviceIds) {
		return """
				{"branchId": "%s", "serviceIds": [%s], %s "start": "%s"}
				""".formatted(branch, ids(serviceIds), staffId == null ? "" : "\"staffId\": \"" + staffId + "\",",
				start);
	}

	/** A walk-in client of the salon. */
	public UUID walkIn(String name) throws Exception {
		return UUID.fromString(read(api.post(owner.accessToken(), path + "/clients", """
				{"displayName": "%s"}
				""".formatted(name)).andExpect(status().isCreated()), "$.id"));
	}

	/** A salon booking by {@code user} (staff) for a client. */
	public ResultActions bookAtSalon(TestUser user, UUID clientId, UUID staffId, Instant start, UUID... serviceIds)
			throws Exception {
		return api.postWithKey(user.accessToken(), path + "/appointments", """
				{"branchId": "%s", "clientId": "%s", "serviceIds": [%s], %s "start": "%s"}
				""".formatted(branchId, clientId, ids(serviceIds),
				staffId == null ? "" : "\"staffId\": \"" + staffId + "\",", start));
	}

	/** The instant of a local time on a day in the branch zone. */
	public static Instant at(LocalDate day, String localTime) {
		return ZonedDateTime.of(day, LocalTime.parse(localTime), ZONE).toInstant();
	}

	private static String ids(UUID... ids) {
		return Arrays.stream(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
	}

	private static String everyDay(String start, String end) {
		List<String> days = Arrays.stream(DayOfWeek.values())
			.map(day -> "{\"dayOfWeek\": \"%s\", \"startTime\": \"%s\", \"endTime\": \"%s\"}".formatted(day, start, end))
			.toList();
		return "[" + String.join(",", days) + "]";
	}

}
