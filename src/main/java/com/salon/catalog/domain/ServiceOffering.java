package com.salon.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A service offered at one branch, with optional overrides of the service's price, duration and
 * buffer. {@code null} overrides fall back to the service defaults.
 */
@Entity
@Table(name = "service_offering")
public class ServiceOffering extends BaseEntity {

	private UUID salonId;

	private UUID serviceId;

	private UUID branchId;

	@Column(precision = 12, scale = 2)
	private BigDecimal priceOverride;

	private Integer durationOverrideMinutes;

	private Integer bufferOverrideMinutes;

	private Instant createdAt;

	private Instant updatedAt;

	protected ServiceOffering() {
	}

	public ServiceOffering(UUID salonId, UUID serviceId, UUID branchId, Overrides overrides, Instant now) {
		this.salonId = salonId;
		this.serviceId = serviceId;
		this.branchId = branchId;
		this.createdAt = now;
		change(overrides, now);
	}

	public void change(Overrides overrides, Instant now) {
		this.priceOverride = overrides.price();
		this.durationOverrideMinutes = overrides.durationMinutes();
		this.bufferOverrideMinutes = overrides.bufferMinutes();
		this.updatedAt = now;
	}

	public BigDecimal effectivePrice(ServiceItem service) {
		return priceOverride != null ? priceOverride : service.getPrice();
	}

	public int effectiveDurationMinutes(ServiceItem service) {
		return durationOverrideMinutes != null ? durationOverrideMinutes : service.getDurationMinutes();
	}

	public int effectiveBufferMinutes(ServiceItem service) {
		return bufferOverrideMinutes != null ? bufferOverrideMinutes : service.getBufferMinutes();
	}

	public UUID getSalonId() {
		return salonId;
	}

	public UUID getServiceId() {
		return serviceId;
	}

	public UUID getBranchId() {
		return branchId;
	}

	public BigDecimal getPriceOverride() {
		return priceOverride;
	}

	public Integer getDurationOverrideMinutes() {
		return durationOverrideMinutes;
	}

	public Integer getBufferOverrideMinutes() {
		return bufferOverrideMinutes;
	}

	/** Per-branch overrides; each {@code null} means "use the service default". */
	public record Overrides(BigDecimal price, Integer durationMinutes, Integer bufferMinutes) {
	}

}
