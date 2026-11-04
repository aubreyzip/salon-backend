package com.salon.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A salon-defined menu section ("Hair", "Nails") for displaying services. Optional; search uses
 * the platform {@link ServiceCategory} instead.
 */
@Entity
@Table(name = "service_section")
public class ServiceSection extends BaseEntity {

	private UUID salonId;

	private String name;

	private int sortOrder;

	private Instant createdAt;

	private Instant updatedAt;

	protected ServiceSection() {
	}

	public ServiceSection(UUID salonId, String name, int sortOrder, Instant now) {
		this.salonId = salonId;
		this.createdAt = now;
		update(name, sortOrder, now);
	}

	public void update(String name, int sortOrder, Instant now) {
		this.name = name;
		this.sortOrder = sortOrder;
		this.updatedAt = now;
	}

	public UUID getSalonId() {
		return salonId;
	}

	public String getName() {
		return name;
	}

	public int getSortOrder() {
		return sortOrder;
	}

}
