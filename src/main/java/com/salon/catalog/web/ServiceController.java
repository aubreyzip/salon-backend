package com.salon.catalog.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.salon.catalog.application.CatalogService;
import com.salon.catalog.application.CatalogViews.ServiceView;
import com.salon.catalog.domain.ServiceDetails;
import com.salon.catalog.domain.ServiceOffering;
import com.salon.salons.ConflictOverride;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/salons/{salonId}/services")
class ServiceController {

	private final CatalogService catalog;

	ServiceController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<ServiceView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@RequestParam(defaultValue = "false") boolean includeArchived) {
		return catalog.listServices(salonId, userId, includeArchived);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ServiceView create(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@Valid @RequestBody ServiceRequest request) {
		return catalog.createService(salonId, userId, request.details());
	}

	@GetMapping("/{serviceId}")
	ServiceView get(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID serviceId) {
		return catalog.getService(salonId, serviceId, userId);
	}

	@PutMapping("/{serviceId}")
	ServiceView update(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID serviceId,
			@Valid @RequestBody ServiceRequest request) {
		return catalog.updateService(salonId, serviceId, userId, request.details());
	}

	@PostMapping("/{serviceId}/archive")
	ServiceView archive(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID serviceId,
			@Valid @RequestBody(required = false) ConflictOverride.Body body) {
		return catalog.archiveService(salonId, serviceId, userId, ConflictOverride.Body.of(body));
	}

	@PostMapping("/{serviceId}/restore")
	ServiceView restore(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID serviceId) {
		return catalog.restoreService(salonId, serviceId, userId);
	}

	/** Offers the service at the branch (or changes its overrides). Omitted overrides use the service defaults. */
	@PutMapping("/{serviceId}/branches/{branchId}")
	ServiceView offer(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID serviceId,
			@PathVariable UUID branchId, @Valid @RequestBody OfferingRequest request) {
		return catalog.offerAtBranch(salonId, serviceId, branchId, userId, request.overrides());
	}

	@DeleteMapping("/{serviceId}/branches/{branchId}")
	ServiceView removeOffering(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID serviceId,
			@PathVariable UUID branchId, @Valid @RequestBody(required = false) ConflictOverride.Body body) {
		return catalog.removeFromBranch(salonId, serviceId, branchId, userId, ConflictOverride.Body.of(body));
	}

	record ServiceRequest(@NotNull Integer categoryId, UUID sectionId, @NotBlank @Size(max = 100) String name,
			@Size(max = 1000) String description, @NotNull @Min(5) @Max(720) Integer durationMinutes,
			@Min(0) @Max(240) Integer bufferMinutes,
			@NotNull @DecimalMin("0") @DecimalMax("9999999999.99") @Digits(integer = 10, fraction = 2) BigDecimal price,
			Boolean priceFrom, Boolean onlineBookable) {

		ServiceDetails details() {
			return new ServiceDetails(categoryId, sectionId, name.strip(), blankToNull(description), durationMinutes,
					bufferMinutes == null ? 0 : bufferMinutes, price, Boolean.TRUE.equals(priceFrom),
					onlineBookable == null || onlineBookable);
		}

	}

	record OfferingRequest(
			@DecimalMin("0") @DecimalMax("9999999999.99") @Digits(integer = 10, fraction = 2) BigDecimal priceOverride,
			@Min(5) @Max(720) Integer durationOverrideMinutes, @Min(0) @Max(240) Integer bufferOverrideMinutes) {

		ServiceOffering.Overrides overrides() {
			return new ServiceOffering.Overrides(priceOverride, durationOverrideMinutes, bufferOverrideMinutes);
		}

	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
