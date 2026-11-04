package com.salon.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.catalog.application.CatalogViews.SkillView;
import com.salon.catalog.domain.CatalogErrors;
import com.salon.catalog.domain.ServiceItemRepository;
import com.salon.catalog.domain.StaffSkill;
import com.salon.catalog.domain.StaffSkillRepository;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which services each staff member performs (ADR-0012). Part of the catalog, so changes need
 * {@code CATALOG_MANAGE}; every member may read them.
 */
@Service
@Transactional
public class StaffSkillService {

	private final StaffSkillRepository skills;

	private final ServiceItemRepository services;

	private final TenantAccess access;

	private final SalonDirectory directory;

	private final AuditLog auditLog;

	private final Clock clock;

	StaffSkillService(StaffSkillRepository skills, ServiceItemRepository services, TenantAccess access,
			SalonDirectory directory, AuditLog auditLog, Clock clock) {
		this.skills = skills;
		this.services = services;
		this.access = access;
		this.directory = directory;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	public record SkillAssignment(UUID serviceId, Integer durationOverrideMinutes) {
	}

	@Transactional(readOnly = true)
	public List<SkillView> list(UUID salonId, UUID staffId, UUID userId) {
		access.requireMember(salonId, userId);
		checkStaff(salonId, staffId);
		return skills.findBySalonIdAndStaffId(salonId, staffId).stream().map(SkillView::of).toList();
	}

	/** Replaces all skills of the staff member. */
	public List<SkillView> replace(UUID salonId, UUID staffId, UUID userId, List<SkillAssignment> assignments) {
		access.require(salonId, userId, SalonPermission.CATALOG_MANAGE);
		directory.ensureModifiable(salonId);
		checkStaff(salonId, staffId);
		Set<UUID> serviceIds = new HashSet<>();
		for (SkillAssignment assignment : assignments) {
			if (!serviceIds.add(assignment.serviceId())) {
				throw CatalogErrors.duplicateService();
			}
		}
		if (services.findBySalonIdAndIdIn(salonId, serviceIds).size() != serviceIds.size()) {
			throw CatalogErrors.serviceNotInSalon();
		}

		skills.deleteBySalonIdAndStaffId(salonId, staffId);
		Instant now = clock.instant();
		List<StaffSkill> saved = skills.saveAll(assignments.stream()
			.map(a -> new StaffSkill(salonId, staffId, a.serviceId(), a.durationOverrideMinutes(), now))
			.toList());
		auditLog.record(new AuditRecord(userId, salonId, "catalog.staff_skills.replaced", "staff_member",
				staffId.toString(), saved.size() + " services"));
		return saved.stream().map(SkillView::of).toList();
	}

	private void checkStaff(UUID salonId, UUID staffId) {
		if (directory.findStaff(salonId, staffId).isEmpty()) {
			throw CatalogErrors.staffNotFound();
		}
	}

}
