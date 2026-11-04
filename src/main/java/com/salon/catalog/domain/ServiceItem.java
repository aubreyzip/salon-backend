package com.salon.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A service a salon sells, with its default price, duration and buffer. It is bookable at a branch
 * only through a {@link ServiceOffering}, and only by staff with a {@link StaffSkill} for it.
 * Services are archived, never deleted, because appointments will reference them (ADR-0012).
 */
@Entity
@Table(name = "service_item")
public class ServiceItem extends BaseEntity {

	/** The only currency until another market opens (ADR-0012). */
	public static final String CURRENCY = "TRY";

	private UUID salonId;

	private Integer categoryId;

	private UUID sectionId;

	private String name;

	private String description;

	private int durationMinutes;

	private int bufferMinutes;

	@Column(precision = 12, scale = 2)
	private BigDecimal price;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	private boolean priceFrom;

	private boolean onlineBookable;

	@Enumerated(EnumType.STRING)
	private ServiceStatus status;

	private Instant createdAt;

	private Instant updatedAt;

	protected ServiceItem() {
	}

	public ServiceItem(UUID salonId, ServiceDetails details, Instant now) {
		this.salonId = salonId;
		this.currency = CURRENCY;
		this.status = ServiceStatus.ACTIVE;
		this.createdAt = now;
		update(details, now);
	}

	public void update(ServiceDetails details, Instant now) {
		this.categoryId = details.categoryId();
		this.sectionId = details.sectionId();
		this.name = details.name();
		this.description = details.description();
		this.durationMinutes = details.durationMinutes();
		this.bufferMinutes = details.bufferMinutes();
		this.price = details.price();
		this.priceFrom = details.priceFrom();
		this.onlineBookable = details.onlineBookable();
		this.updatedAt = now;
	}

	public void archive(Instant now) {
		this.status = ServiceStatus.ARCHIVED;
		this.updatedAt = now;
	}

	public void restore(Instant now) {
		this.status = ServiceStatus.ACTIVE;
		this.updatedAt = now;
	}

	public void removeSection(Instant now) {
		this.sectionId = null;
		this.updatedAt = now;
	}

	public boolean isActive() {
		return status == ServiceStatus.ACTIVE;
	}

	public UUID getSalonId() {
		return salonId;
	}

	public Integer getCategoryId() {
		return categoryId;
	}

	public UUID getSectionId() {
		return sectionId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public int getBufferMinutes() {
		return bufferMinutes;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public String getCurrency() {
		return currency;
	}

	public boolean isPriceFrom() {
		return priceFrom;
	}

	public boolean isOnlineBookable() {
		return onlineBookable;
	}

	public ServiceStatus getStatus() {
		return status;
	}

}
