package com.salon.catalog.web;

import java.util.List;
import java.util.UUID;

import com.salon.catalog.application.CatalogService;
import com.salon.catalog.application.CatalogViews.CategoryView;
import com.salon.catalog.application.CatalogViews.PublicServiceView;
import com.salon.catalog.application.PublicCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public catalog data: the platform service categories and the services bookable online at a
 * branch. Branches that are inactive, or of salons that are not active, are not found.
 */
@RestController
@RequestMapping("/api/v1/public")
class PublicCatalogController {

	private final CatalogService catalog;

	private final PublicCatalogService publicCatalog;

	PublicCatalogController(CatalogService catalog, PublicCatalogService publicCatalog) {
		this.catalog = catalog;
		this.publicCatalog = publicCatalog;
	}

	@GetMapping("/service-categories")
	List<CategoryView> categories() {
		return catalog.categories();
	}

	@GetMapping("/branches/{branchId}/services")
	List<PublicServiceView> branchServices(@PathVariable UUID branchId) {
		return publicCatalog.branchServices(branchId);
	}

}
