package com.salon.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.salon.catalog.BookingRequirements.RequiredService;
import com.salon.catalog.domain.CatalogErrors;
import com.salon.catalog.domain.ServiceItem;
import com.salon.catalog.domain.ServiceItemRepository;
import com.salon.catalog.domain.ServiceOffering;
import com.salon.catalog.domain.ServiceOfferingRepository;
import com.salon.catalog.domain.StaffSkill;
import com.salon.catalog.domain.StaffSkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog lookups for scheduling and booking. Callers have already checked access to the salon
 * (or that the branch is public).
 */
@Service
@Transactional(readOnly = true)
public class ServiceCatalog {

	private final ServiceItemRepository services;

	private final ServiceOfferingRepository offerings;

	private final StaffSkillRepository skills;

	ServiceCatalog(ServiceItemRepository services, ServiceOfferingRepository offerings, StaffSkillRepository skills) {
		this.services = services;
		this.offerings = offerings;
		this.skills = skills;
	}

	/**
	 * Resolves what booking the given services at the branch takes.
	 * @param onlineBookableOnly reject services customers may not book online
	 * @throws com.salon.shared.ApiException {@code 400 SERVICE_NOT_OFFERED} if a service does not
	 * exist in the salon, is archived, is not offered at the branch, or (when requested) is not
	 * bookable online
	 */
	public BookingRequirements requirements(UUID salonId, UUID branchId, List<UUID> serviceIds,
			boolean onlineBookableOnly) {
		Set<UUID> ids = new LinkedHashSet<>(serviceIds);
		Map<UUID, ServiceItem> serviceById = services.findBySalonIdAndIdIn(salonId, ids)
			.stream()
			.collect(Collectors.toMap(ServiceItem::getId, Function.identity()));
		Map<UUID, ServiceOffering> offeringByService = offerings
			.findBySalonIdAndBranchIdAndServiceIdIn(salonId, branchId, ids)
			.stream()
			.collect(Collectors.toMap(ServiceOffering::getServiceId, Function.identity()));

		List<RequiredService> required = new ArrayList<>();
		int buffer = 0;
		for (UUID id : ids) {
			ServiceItem service = serviceById.get(id);
			ServiceOffering offering = offeringByService.get(id);
			if (service == null || offering == null || !service.isActive()
					|| (onlineBookableOnly && !service.isOnlineBookable())) {
				throw CatalogErrors.serviceNotOffered();
			}
			required.add(new RequiredService(id, service.getName(), offering.effectiveDurationMinutes(service),
					offering.effectiveBufferMinutes(service), offering.effectivePrice(service), service.getCurrency(),
					service.isPriceFrom()));
			buffer = Math.max(buffer, offering.effectiveBufferMinutes(service));
		}
		return new BookingRequirements(required, buffer, serviceDurationsByStaff(salonId, required));
	}

	/** Staff with a skill for every service, and their duration per service. */
	private Map<UUID, Map<UUID, Integer>> serviceDurationsByStaff(UUID salonId, List<RequiredService> required) {
		Map<UUID, Integer> branchDuration = required.stream()
			.collect(Collectors.toMap(RequiredService::serviceId, RequiredService::durationMinutes));
		Map<UUID, List<StaffSkill>> skillsByStaff = skills.findBySalonIdAndServiceIdIn(salonId, branchDuration.keySet())
			.stream()
			.collect(Collectors.groupingBy(StaffSkill::getStaffId));
		Map<UUID, Map<UUID, Integer>> result = new HashMap<>();
		skillsByStaff.forEach((staffId, staffSkills) -> {
			if (staffSkills.size() == branchDuration.size()) {
				result.put(staffId, staffSkills.stream()
					.collect(Collectors.toMap(StaffSkill::getServiceId,
							skill -> skill.getDurationOverrideMinutes() != null ? skill.getDurationOverrideMinutes()
									: branchDuration.get(skill.getServiceId()))));
			}
		});
		return result;
	}

}
