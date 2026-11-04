package com.salon.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped: every lookup takes the salon id (ADR-0002).
 */
public interface StaffSkillRepository extends JpaRepository<StaffSkill, UUID> {

	List<StaffSkill> findBySalonIdAndStaffId(UUID salonId, UUID staffId);

	List<StaffSkill> findBySalonIdAndServiceIdIn(UUID salonId, Collection<UUID> serviceIds);

	/**
	 * Deletes immediately (not at flush), so replacement rows can be inserted in the same
	 * transaction without hitting the unique constraint.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from StaffSkill s where s.salonId = :salonId and s.staffId = :staffId")
	void deleteBySalonIdAndStaffId(@Param("salonId") UUID salonId, @Param("staffId") UUID staffId);

}
