package com.salon.salons.web;

import java.util.List;
import java.util.UUID;

import com.salon.salons.application.BranchService;
import com.salon.salons.application.BranchView;
import com.salon.salons.domain.Address;
import com.salon.salons.domain.BookingPolicy;
import com.salon.salons.domain.BranchDetails;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/salons/{salonId}/branches")
class BranchController {

	private final BranchService branches;

	BranchController(BranchService branches) {
		this.branches = branches;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	BranchView create(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@Valid @RequestBody BranchRequest request) {
		return branches.create(salonId, userId, request.details(), request.policy());
	}

	@GetMapping
	List<BranchView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId) {
		return branches.list(salonId, userId);
	}

	@GetMapping("/{branchId}")
	BranchView get(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId) {
		return branches.get(salonId, branchId, userId);
	}

	/** Replaces the branch details; the booking policy is only changed when included. */
	@PutMapping("/{branchId}")
	BranchView update(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId,
			@Valid @RequestBody BranchRequest request) {
		return branches.update(salonId, branchId, userId, request.details(), request.policy());
	}

	@PostMapping("/{branchId}/activate")
	BranchView activate(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId) {
		return branches.activate(salonId, branchId, userId);
	}

	@PostMapping("/{branchId}/deactivate")
	BranchView deactivate(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID branchId) {
		return branches.deactivate(salonId, branchId, userId);
	}

	record BranchRequest(@NotBlank @Size(max = 100) String name,
			@Size(max = 20) @Pattern(regexp = "^[0-9+()\\s-]*$") String phone, @NotNull @Valid AddressRequest address,
			@DecimalMin("-90") @DecimalMax("90") Double latitude,
			@DecimalMin("-180") @DecimalMax("180") Double longitude, @Size(max = 64) String timeZone,
			@Valid BookingPolicyRequest bookingPolicy) {

		BranchDetails details() {
			return new BranchDetails(name.strip(), blankToNull(phone), address.toAddress(), latitude, longitude,
					timeZone);
		}

		BookingPolicy policy() {
			return bookingPolicy == null ? null : bookingPolicy.toPolicy();
		}

	}

	record AddressRequest(@NotNull @Min(1) @Max(81) Integer provinceId, @NotBlank @Size(max = 100) String district,
			@Size(max = 100) String neighbourhood, @NotBlank @Size(max = 300) String addressLine,
			@Size(max = 10) String postalCode) {

		Address toAddress() {
			return new Address(provinceId, district.strip(), blankToNull(neighbourhood), addressLine.strip(),
					blankToNull(postalCode));
		}

	}

	record BookingPolicyRequest(@NotNull Boolean autoConfirm, @NotNull Integer slotIntervalMinutes,
			@NotNull @Min(0) @Max(10080) Integer minNoticeMinutes, @NotNull @Min(1) @Max(365) Integer maxAdvanceDays,
			@NotNull @Min(0) @Max(10080) Integer cancellationCutoffMinutes,
			@NotNull @Min(15) @Max(2880) Integer pendingConfirmationTimeoutMinutes) {

		BookingPolicy toPolicy() {
			return new BookingPolicy(autoConfirm, slotIntervalMinutes, minNoticeMinutes, maxAdvanceDays,
					cancellationCutoffMinutes, pendingConfirmationTimeoutMinutes);
		}

	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
