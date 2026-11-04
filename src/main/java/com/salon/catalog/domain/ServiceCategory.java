package com.salon.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Reference data: a platform-wide service category (taxonomy) that every salon service maps to,
 * so public search can find services across salons. Seeded by migration.
 */
@Entity
@Immutable
@Table(name = "service_category")
public class ServiceCategory {

	@Id
	private Integer id;

	private String code;

	private String name;

	private int sortOrder;

	protected ServiceCategory() {
	}

	public Integer getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public int getSortOrder() {
		return sortOrder;
	}

}
