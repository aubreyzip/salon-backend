package com.salon.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.catalog.application.CatalogViews.CategoryView;
import com.salon.catalog.application.CatalogViews.SectionView;
import com.salon.catalog.application.CatalogViews.ServiceView;
import com.salon.catalog.domain.CatalogErrors;
import com.salon.catalog.domain.ServiceCategoryRepository;
import com.salon.catalog.domain.ServiceDetails;
import com.salon.catalog.domain.ServiceItem;
import com.salon.catalog.domain.ServiceItemRepository;
import com.salon.catalog.domain.ServiceOffering;
import com.salon.catalog.domain.ServiceOfferingRepository;
import com.salon.catalog.domain.ServiceSection;
import com.salon.catalog.domain.ServiceSectionRepository;
import com.salon.catalog.domain.ServiceStatus;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Salon catalog management: sections, services and branch offerings (ADR-0012). Changes need
 * {@code CATALOG_MANAGE} (salon-level, ADR-0010); every member may read the catalog.
 */
@Service
@Transactional
public class CatalogService {

	private static final String ENTITY_SERVICE = "service_item";

	private static final String ENTITY_SECTION = "service_section";

	private final ServiceCategoryRepository categories;

	private final ServiceSectionRepository sections;

	private final ServiceItemRepository services;

	private final ServiceOfferingRepository offerings;

	private final TenantAccess access;

	private final SalonDirectory directory;

	private final AuditLog auditLog;

	private final Clock clock;

	CatalogService(ServiceCategoryRepository categories, ServiceSectionRepository sections,
			ServiceItemRepository services, ServiceOfferingRepository offerings, TenantAccess access,
			SalonDirectory directory, AuditLog auditLog, Clock clock) {
		this.categories = categories;
		this.sections = sections;
		this.services = services;
		this.offerings = offerings;
		this.access = access;
		this.directory = directory;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CategoryView> categories() {
		return categories.findAllByOrderBySortOrderAsc().stream().map(CategoryView::of).toList();
	}

	@Transactional(readOnly = true)
	public List<SectionView> listSections(UUID salonId, UUID userId) {
		access.requireMember(salonId, userId);
		return sections.findBySalonIdOrderBySortOrderAscNameAsc(salonId).stream().map(SectionView::of).toList();
	}

	public SectionView createSection(UUID salonId, UUID userId, String name, int sortOrder) {
		checkManage(salonId, userId);
		ServiceSection section = sections.save(new ServiceSection(salonId, name, sortOrder, clock.instant()));
		audit(userId, salonId, "catalog.section.created", ENTITY_SECTION, section.getId());
		return SectionView.of(section);
	}

	public SectionView updateSection(UUID salonId, UUID sectionId, UUID userId, String name, int sortOrder) {
		checkManage(salonId, userId);
		ServiceSection section = loadSection(salonId, sectionId);
		section.update(name, sortOrder, clock.instant());
		audit(userId, salonId, "catalog.section.updated", ENTITY_SECTION, sectionId);
		return SectionView.of(section);
	}

	/** Deletes the section; its services stay, without a section. */
	public void deleteSection(UUID salonId, UUID sectionId, UUID userId) {
		checkManage(salonId, userId);
		ServiceSection section = loadSection(salonId, sectionId);
		Instant now = clock.instant();
		services.findBySalonIdAndSectionId(salonId, sectionId).forEach(service -> service.removeSection(now));
		services.flush();
		sections.delete(section);
		audit(userId, salonId, "catalog.section.deleted", ENTITY_SECTION, sectionId);
	}

	@Transactional(readOnly = true)
	public List<ServiceView> listServices(UUID salonId, UUID userId, boolean includeArchived) {
		access.requireMember(salonId, userId);
		Map<UUID, List<ServiceOffering>> offeringsByService = offerings.findBySalonId(salonId)
			.stream()
			.collect(Collectors.groupingBy(ServiceOffering::getServiceId));
		List<ServiceItem> items = includeArchived ? services.findBySalonIdOrderByNameAsc(salonId)
				: services.findBySalonIdAndStatusOrderByNameAsc(salonId, ServiceStatus.ACTIVE);
		return items.stream()
			.map(service -> ServiceView.of(service, offeringsByService.getOrDefault(service.getId(), List.of())))
			.toList();
	}

	@Transactional(readOnly = true)
	public ServiceView getService(UUID salonId, UUID serviceId, UUID userId) {
		access.requireMember(salonId, userId);
		return view(loadService(salonId, serviceId));
	}

	public ServiceView createService(UUID salonId, UUID userId, ServiceDetails details) {
		checkManage(salonId, userId);
		checkReferences(salonId, details);
		ServiceItem service = services.save(new ServiceItem(salonId, details, clock.instant()));
		audit(userId, salonId, "catalog.service.created", ENTITY_SERVICE, service.getId());
		return view(service);
	}

	public ServiceView updateService(UUID salonId, UUID serviceId, UUID userId, ServiceDetails details) {
		return changeService(salonId, serviceId, userId, "catalog.service.updated", (service, now) -> {
			checkReferences(salonId, details);
			service.update(details, now);
		});
	}

	/** Archived services are no longer bookable; offerings and skills are kept for a restore. */
	public ServiceView archiveService(UUID salonId, UUID serviceId, UUID userId) {
		return changeService(salonId, serviceId, userId, "catalog.service.archived", ServiceItem::archive);
	}

	public ServiceView restoreService(UUID salonId, UUID serviceId, UUID userId) {
		return changeService(salonId, serviceId, userId, "catalog.service.restored", ServiceItem::restore);
	}

	/** Offers the service at the branch, or changes the overrides of an existing offering. */
	public ServiceView offerAtBranch(UUID salonId, UUID serviceId, UUID branchId, UUID userId,
			ServiceOffering.Overrides overrides) {
		return changeService(salonId, serviceId, userId, "catalog.service.offering_changed", (service, now) -> {
			if (!service.isActive()) {
				throw CatalogErrors.serviceArchived();
			}
			checkBranch(salonId, branchId);
			offerings.findBySalonIdAndServiceIdAndBranchId(salonId, serviceId, branchId)
				.ifPresentOrElse(offering -> offering.change(overrides, now), () -> offerings
					.save(new ServiceOffering(salonId, serviceId, branchId, overrides, now)));
		});
	}

	/** Stops offering the service at the branch. Does nothing if it is not offered there. */
	public ServiceView removeFromBranch(UUID salonId, UUID serviceId, UUID branchId, UUID userId) {
		return changeService(salonId, serviceId, userId, "catalog.service.offering_removed", (service, now) -> {
			checkBranch(salonId, branchId);
			offerings.findBySalonIdAndServiceIdAndBranchId(salonId, serviceId, branchId).ifPresent(offerings::delete);
		});
	}

	private ServiceView changeService(UUID salonId, UUID serviceId, UUID userId, String action,
			BiConsumer<ServiceItem, Instant> change) {
		checkManage(salonId, userId);
		ServiceItem service = loadService(salonId, serviceId);
		change.accept(service, clock.instant());
		offerings.flush();
		audit(userId, salonId, action, ENTITY_SERVICE, serviceId);
		return view(service);
	}

	private void checkManage(UUID salonId, UUID userId) {
		access.require(salonId, userId, SalonPermission.CATALOG_MANAGE);
		directory.ensureModifiable(salonId);
	}

	private void checkReferences(UUID salonId, ServiceDetails details) {
		if (!categories.existsById(details.categoryId())) {
			throw CatalogErrors.unknownCategory();
		}
		if (details.sectionId() != null && !sections.existsByIdAndSalonId(details.sectionId(), salonId)) {
			throw CatalogErrors.unknownSection();
		}
	}

	private void checkBranch(UUID salonId, UUID branchId) {
		if (directory.findBranch(salonId, branchId).isEmpty()) {
			throw CatalogErrors.branchNotFound();
		}
	}

	private ServiceSection loadSection(UUID salonId, UUID sectionId) {
		return sections.findByIdAndSalonId(sectionId, salonId).orElseThrow(CatalogErrors::sectionNotFound);
	}

	private ServiceItem loadService(UUID salonId, UUID serviceId) {
		return services.findByIdAndSalonId(serviceId, salonId).orElseThrow(CatalogErrors::serviceNotFound);
	}

	private ServiceView view(ServiceItem service) {
		return ServiceView.of(service, offerings.findBySalonIdAndServiceId(service.getSalonId(), service.getId()));
	}

	private void audit(UUID userId, UUID salonId, String action, String entityType, UUID entityId) {
		auditLog.record(new AuditRecord(userId, salonId, action, entityType, entityId.toString(), null));
	}

}
