package com.salon.catalog.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.salon.catalog.application.CatalogViews.PublicServiceView;
import com.salon.catalog.domain.CatalogErrors;
import com.salon.catalog.domain.ServiceItem;
import com.salon.catalog.domain.ServiceItemRepository;
import com.salon.catalog.domain.ServiceOffering;
import com.salon.catalog.domain.ServiceOfferingRepository;
import com.salon.catalog.domain.ServiceSection;
import com.salon.catalog.domain.ServiceSectionRepository;
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The services customers can book online at a public branch (active branch of an active salon).
 */
@Service
@Transactional(readOnly = true)
public class PublicCatalogService {

	private final ServiceItemRepository services;

	private final ServiceOfferingRepository offerings;

	private final ServiceSectionRepository sections;

	private final SalonDirectory directory;

	PublicCatalogService(ServiceItemRepository services, ServiceOfferingRepository offerings,
			ServiceSectionRepository sections, SalonDirectory directory) {
		this.services = services;
		this.offerings = offerings;
		this.sections = sections;
		this.directory = directory;
	}

	public List<PublicServiceView> branchServices(UUID branchId) {
		BranchInfo branch = directory.findPublicBranch(branchId).orElseThrow(CatalogErrors::branchNotFound);
		UUID salonId = branch.salonId();
		Map<UUID, ServiceOffering> offeringByService = offerings.findBySalonIdAndBranchId(salonId, branchId)
			.stream()
			.collect(Collectors.toMap(ServiceOffering::getServiceId, Function.identity()));
		Map<UUID, ServiceSection> sectionById = sections.findBySalonIdOrderBySortOrderAscNameAsc(salonId)
			.stream()
			.collect(Collectors.toMap(ServiceSection::getId, Function.identity()));
		return services.findBySalonIdAndIdIn(salonId, offeringByService.keySet())
			.stream()
			.filter(service -> service.isActive() && service.isOnlineBookable())
			.sorted(Comparator.comparing((ServiceItem service) -> sectionOrder(sectionById, service))
				.thenComparing(ServiceItem::getName))
			.map(service -> view(service, offeringByService.get(service.getId()), sectionById))
			.toList();
	}

	private static int sectionOrder(Map<UUID, ServiceSection> sectionById, ServiceItem service) {
		ServiceSection section = sectionById.get(service.getSectionId());
		return section == null ? Integer.MAX_VALUE : section.getSortOrder();
	}

	private static PublicServiceView view(ServiceItem service, ServiceOffering offering,
			Map<UUID, ServiceSection> sectionById) {
		ServiceSection section = sectionById.get(service.getSectionId());
		return new PublicServiceView(service.getId(), service.getCategoryId(), service.getSectionId(),
				section == null ? null : section.getName(), service.getName(), service.getDescription(),
				offering.effectiveDurationMinutes(service), offering.effectivePrice(service), service.getCurrency(),
				service.isPriceFrom());
	}

}
