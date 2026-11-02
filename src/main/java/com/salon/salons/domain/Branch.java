package com.salon.salons.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A physical location of a salon. Customers discover and book at branch level (ADR-0008).
 */
@Entity
@Table(name = "branch")
public class Branch extends BaseEntity {

	public static final String DEFAULT_TIME_ZONE = "Europe/Istanbul";

	private UUID salonId;

	private String name;

	private String slug;

	private String phone;

	@Embedded
	private Address address;

	private Double latitude;

	private Double longitude;

	private String timeZone;

	@Enumerated(EnumType.STRING)
	private BranchStatus status;

	@Embedded
	private BookingPolicy bookingPolicy;

	private Instant createdAt;

	private Instant updatedAt;

	protected Branch() {
	}

	public Branch(UUID salonId, String slug, BranchDetails details, BookingPolicy bookingPolicy, Instant now) {
		this.salonId = salonId;
		this.slug = slug;
		this.status = BranchStatus.ACTIVE;
		this.bookingPolicy = bookingPolicy.validated();
		this.createdAt = now;
		apply(details, now);
	}

	public void update(BranchDetails details, Instant now) {
		apply(details, now);
	}

	public void changeBookingPolicy(BookingPolicy policy, Instant now) {
		this.bookingPolicy = policy.validated();
		this.updatedAt = now;
	}

	public void activate(Instant now) {
		this.status = BranchStatus.ACTIVE;
		this.updatedAt = now;
	}

	public void deactivate(Instant now) {
		this.status = BranchStatus.INACTIVE;
		this.updatedAt = now;
	}

	public boolean isActive() {
		return status == BranchStatus.ACTIVE;
	}

	public boolean hasLocation() {
		return latitude != null && longitude != null;
	}

	private void apply(BranchDetails details, Instant now) {
		if ((details.latitude() == null) != (details.longitude() == null)) {
			throw SalonErrors.invalidCoordinates();
		}
		this.name = details.name();
		this.phone = details.phone();
		this.address = details.address();
		this.latitude = details.latitude();
		this.longitude = details.longitude();
		this.timeZone = validTimeZone(details.timeZone());
		this.updatedAt = now;
	}

	private static String validTimeZone(String timeZone) {
		if (timeZone == null || timeZone.isBlank()) {
			return DEFAULT_TIME_ZONE;
		}
		// Only region ids (e.g. Europe/Istanbul); fixed offsets would ignore future rule changes.
		if (!timeZone.contains("/")) {
			throw SalonErrors.invalidTimeZone();
		}
		try {
			return ZoneId.of(timeZone).getId();
		}
		catch (DateTimeException ex) {
			throw SalonErrors.invalidTimeZone();
		}
	}

	public UUID getSalonId() {
		return salonId;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public String getPhone() {
		return phone;
	}

	public Address getAddress() {
		return address;
	}

	public Double getLatitude() {
		return latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public String getTimeZone() {
		return timeZone;
	}

	public BookingPolicy getBookingPolicy() {
		return bookingPolicy;
	}

}
