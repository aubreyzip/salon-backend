package com.salon.catalog.web;

import java.util.List;
import java.util.UUID;

import com.salon.catalog.application.CatalogViews.SkillView;
import com.salon.catalog.application.StaffSkillService;
import com.salon.catalog.application.StaffSkillService.SkillAssignment;
import com.salon.shared.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The services a staff member performs. Without a skill, the staff member is not offered for the
 * service.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/staff/{staffId}/skills")
class StaffSkillController {

	private final StaffSkillService skills;

	StaffSkillController(StaffSkillService skills) {
		this.skills = skills;
	}

	@GetMapping
	List<SkillView> list(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId) {
		return skills.list(salonId, staffId, userId);
	}

	/** Replaces all skills of the staff member. */
	@PutMapping
	List<SkillView> replace(@CurrentUserId UUID userId, @PathVariable UUID salonId, @PathVariable UUID staffId,
			@Valid @RequestBody SkillsRequest request) {
		return skills.replace(salonId, staffId, userId,
				request.skills().stream().map(SkillRequest::assignment).toList());
	}

	record SkillsRequest(@NotNull @Size(max = 200) List<@NotNull @Valid SkillRequest> skills) {
	}

	record SkillRequest(@NotNull UUID serviceId, @Min(5) @Max(720) Integer durationOverrideMinutes) {

		SkillAssignment assignment() {
			return new SkillAssignment(serviceId, durationOverrideMinutes);
		}

	}

}
