package com.salon.scheduling;

import java.util.List;
import java.util.UUID;

import com.salon.IntegrationTest;
import com.salon.TestApi;
import com.salon.TestApi.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Local schedule times are stored exactly as entered (ADR-0006: {@code LocalTime} + branch zone),
 * whatever the JVM's default time zone. Regression: with {@code hibernate.jdbc.time_zone=UTC},
 * Hibernate shifted {@code time} values by the JVM offset (09:00 was stored as 06:00 on a +03:00
 * machine), so intervals crossing the offset (e.g. 00:00–23:59) violated the order CHECK.
 */
@IntegrationTest
class LocalTimeStorageIntegrationTests {

	@Autowired
	TestApi api;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void openingHoursAreStoredAsEnteredAndRoundTrip() throws Exception {
		TestUser owner = api.signUp("Time Owner");
		UUID salonId = api.createSalon(owner, "Time Salon");
		UUID branchId = api.createBranch(owner, salonId);
		String hours = "/api/v1/salons/" + salonId + "/branches/" + branchId + "/opening-hours";

		api.put(owner.accessToken(), hours, """
				{"intervals": [{"dayOfWeek": "MONDAY", "startTime": "00:00", "endTime": "02:30"},
				               {"dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "23:59"}]}
				""").andExpect(status().isOk());

		List<String> stored = jdbc.queryForList("""
				SELECT i.start_time::text || '-' || i.end_time::text FROM branch_hours_interval i
				JOIN branch_hours h ON h.id = i.branch_hours_id WHERE h.branch_id = ? ORDER BY i.start_time
				""", String.class, branchId);
		assertThat(stored).containsExactly("00:00:00-02:30:00", "09:00:00-23:59:00");
		api.get(owner.accessToken(), hours)
			.andExpect(jsonPath("$.intervals[0].startTime").value("00:00:00"))
			.andExpect(jsonPath("$.intervals[1].endTime").value("23:59:00"));
	}

	@Test
	void datesAreStoredAsEntered() throws Exception {
		TestUser owner = api.signUp("Date Owner");
		UUID salonId = api.createSalon(owner, "Date Salon");
		UUID branchId = api.createBranch(owner, salonId);

		api.put(owner.accessToken(), "/api/v1/salons/" + salonId + "/branches/" + branchId + "/special-days/2030-01-07",
				"{\"intervals\": [{\"startTime\": \"10:00\", \"endTime\": \"12:00\"}]}").andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT on_date::text FROM branch_special_day WHERE branch_id = ?", String.class,
				branchId)).isEqualTo("2030-01-07");
	}

}
