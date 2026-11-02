package com.salon.salons.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Reference data: one of the 81 provinces (il), keyed by licence-plate code. Seeded by migration.
 */
@Entity
@Immutable
@Table(name = "province")
public class Province {

	@Id
	private Integer id;

	private String name;

	protected Province() {
	}

	public Integer getId() {
		return id;
	}

	public String getName() {
		return name;
	}

}
