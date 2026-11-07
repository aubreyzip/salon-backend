package com.salon.booking.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link BookingJobs}: expiry every minute (decision 31), idempotency cleanup hourly.
 * Disabled with {@code salon.booking.jobs.enabled=false} (tests run the jobs directly).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "salon.booking.jobs.enabled", matchIfMissing = true)
class BookingScheduler {

	private final BookingJobs jobs;

	BookingScheduler(BookingJobs jobs) {
		this.jobs = jobs;
	}

	@Scheduled(fixedDelayString = "${salon.booking.jobs.expiry-interval:PT1M}", initialDelayString = "PT30S")
	void expirePendingAppointments() {
		jobs.expireDuePendingAppointments();
	}

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	void deleteOldIdempotencyRecords() {
		jobs.deleteOldIdempotencyRecords();
	}

}
