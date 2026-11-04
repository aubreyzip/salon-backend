package com.salon.catalog.web;

import java.util.List;
import java.util.UUID;

import com.salon.catalog.application.CatalogService;
import com.salon.catalog.application.CatalogViews.SectionView;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Salon-defined menu sections for displaying services.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/service-sections")
class ServiceSectionController {

	private final CatalogService catalog;

	ServiceSectionController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<SectionView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId) {
		return catalog.listSections(salonId, userId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SectionView create(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@Valid @RequestBody SectionRequest request) {
		return catalog.createSection(salonId, userId, request.name().strip(), request.order());
	}

	@PutMapping("/{sectionId}")
	SectionView update(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID sectionId,
			@Valid @RequestBody SectionRequest request) {
		return catalog.updateSection(salonId, sectionId, userId, request.name().strip(), request.order());
	}

	/** Deletes the section; its services are kept without a section. */
	@DeleteMapping("/{sectionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID sectionId) {
		catalog.deleteSection(salonId, sectionId, userId);
	}

	record SectionRequest(@NotBlank @Size(max = 100) String name, @Min(0) @Max(10_000) Integer sortOrder) {

		int order() {
			return sortOrder == null ? 0 : sortOrder;
		}

	}

}
