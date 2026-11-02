package com.salon.salons.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.salon.salons.StaffRole;
import com.salon.salons.application.StaffService;
import com.salon.salons.application.StaffView;
import com.salon.salons.domain.StaffProfile;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/salons/{salonId}/staff")
class StaffController {

	private final StaffService staff;

	StaffController(StaffService staff) {
		this.staff = staff;
	}

	@GetMapping
	List<StaffView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId) {
		return staff.list(salonId, userId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	StaffView create(@CurrentUserId UUID userId, @PathVariable UUID salonId,
			@Valid @RequestBody CreateStaffRequest request) {
		return staff.create(salonId, userId, request.profile(), request.role(), request.branches(),
				request.inviteEmail());
	}

	@PutMapping("/{staffId}")
	StaffView update(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId,
			@Valid @RequestBody StaffRequest request) {
		return staff.update(salonId, staffId, userId, request.profile(), request.role(), request.branches());
	}

	@PostMapping("/{staffId}/deactivate")
	StaffView deactivate(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId) {
		return staff.deactivate(salonId, staffId, userId);
	}

	@PostMapping("/{staffId}/reactivate")
	StaffView reactivate(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId) {
		return staff.reactivate(salonId, staffId, userId);
	}

	/** Cancels the open invitation; the staff member itself is unchanged. */
	@DeleteMapping("/{staffId}/invitations")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancelInvitations(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId) {
		staff.cancelInvitations(salonId, staffId, userId);
	}

	/** Sends (or re-sends) an invitation to link an account to this staff member. */
	@PostMapping("/{staffId}/invitations")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void invite(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId,
			@Valid @RequestBody InviteRequest request) {
		staff.invite(salonId, staffId, userId, request.email());
	}

	record CreateStaffRequest(@NotBlank @Size(max = 100) String displayName, @Size(max = 100) String title,
			@Size(max = 1000) String bio, @NotNull StaffRole role, @NotNull Boolean bookable, Set<UUID> branchIds,
			@Email @Size(max = 254) String inviteEmail) {

		StaffProfile profile() {
			return new StaffProfile(displayName.strip(), blankToNull(title), blankToNull(bio), bookable);
		}

		Set<UUID> branches() {
			return branchIds == null ? Set.of() : branchIds;
		}

	}

	record StaffRequest(@NotBlank @Size(max = 100) String displayName, @Size(max = 100) String title,
			@Size(max = 1000) String bio, @NotNull StaffRole role, @NotNull Boolean bookable, Set<UUID> branchIds) {

		StaffProfile profile() {
			return new StaffProfile(displayName.strip(), blankToNull(title), blankToNull(bio), bookable);
		}

		Set<UUID> branches() {
			return branchIds == null ? Set.of() : branchIds;
		}

	}

	record InviteRequest(@NotBlank @Email @Size(max = 254) String email) {
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
