package com.salon.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.salon.catalog.domain.ServiceCategory;
import com.salon.catalog.domain.ServiceItem;
import com.salon.catalog.domain.ServiceOffering;
import com.salon.catalog.domain.ServiceSection;
import com.salon.catalog.domain.StaffSkill;

/**
 * Response views of the catalog API.
 */
public final class CatalogViews {

	private CatalogViews() {
	}

	public record CategoryView(int id, String code, String name) {

		static CategoryView of(ServiceCategory category) {
			return new CategoryView(category.getId(), category.getCode(), category.getName());
		}

	}

	public record SectionView(UUID id, String name, int sortOrder) {

		static SectionView of(ServiceSection section) {
			return new SectionView(section.getId(), section.getName(), section.getSortOrder());
		}

	}

	/**
	 * @param offerings the branches the service is offered at
	 */
	public record ServiceView(UUID id, int categoryId, UUID sectionId, String name, String description,
			int durationMinutes, int bufferMinutes, BigDecimal price, String currency, boolean priceFrom,
			boolean onlineBookable, boolean archived, List<OfferingView> offerings) {

		static ServiceView of(ServiceItem service, List<ServiceOffering> offerings) {
			return new ServiceView(service.getId(), service.getCategoryId(), service.getSectionId(), service.getName(),
					service.getDescription(), service.getDurationMinutes(), service.getBufferMinutes(),
					service.getPrice(), service.getCurrency(), service.isPriceFrom(), service.isOnlineBookable(),
					!service.isActive(), offerings.stream().map(offering -> OfferingView.of(service, offering)).toList());
		}

	}

	/**
	 * A branch offering: the overrides as configured ({@code null} = service default) and the
	 * resulting effective values.
	 */
	public record OfferingView(UUID branchId, BigDecimal priceOverride, Integer durationOverrideMinutes,
			Integer bufferOverrideMinutes, BigDecimal price, int durationMinutes, int bufferMinutes) {

		static OfferingView of(ServiceItem service, ServiceOffering offering) {
			return new OfferingView(offering.getBranchId(), offering.getPriceOverride(),
					offering.getDurationOverrideMinutes(), offering.getBufferOverrideMinutes(),
					offering.effectivePrice(service), offering.effectiveDurationMinutes(service),
					offering.effectiveBufferMinutes(service));
		}

	}

	/**
	 * @param durationOverrideMinutes the staff member's own duration for the service, or {@code null}
	 */
	public record SkillView(UUID serviceId, Integer durationOverrideMinutes) {

		static SkillView of(StaffSkill skill) {
			return new SkillView(skill.getServiceId(), skill.getDurationOverrideMinutes());
		}

	}

	/** A service as customers see it at one branch (branch overrides applied, no internals). */
	public record PublicServiceView(UUID id, int categoryId, UUID sectionId, String sectionName, String name,
			String description, int durationMinutes, BigDecimal price, String currency, boolean priceFrom) {
	}

}
