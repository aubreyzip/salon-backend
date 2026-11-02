package com.salon.shared;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;

/**
 * Base class for entities with an application-assigned UUIDv7 id and optimistic locking.
 * <p>
 * Because the id is assigned on construction, Spring Data decides whether an entity is new
 * from the {@code version} field ({@code null} until first persisted).
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Id
	private UUID id = Ids.newId();

	@Version
	private Long version;

	public UUID getId() {
		return id;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		return Objects.equals(id, ((BaseEntity) other).id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}

}
