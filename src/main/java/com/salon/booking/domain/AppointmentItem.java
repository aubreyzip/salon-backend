package com.salon.booking.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A booked service with its name, duration and price as booked (snapshot, ADR-0012): later catalog
 * changes do not change existing appointments.
 *
 * @param durationMinutes how long the assigned staff member takes for it
 * @param priceFrom the price is a starting price
 */
@Embeddable
public record AppointmentItem(UUID serviceId, String serviceName, int durationMinutes,
		@Column(precision = 12, scale = 2) BigDecimal price,
		@JdbcTypeCode(SqlTypes.CHAR) @Column(length = 3) String currency, boolean priceFrom) {

	public AppointmentItem withDuration(int newDurationMinutes) {
		return new AppointmentItem(serviceId, serviceName, newDurationMinutes, price, currency, priceFrom);
	}

}
