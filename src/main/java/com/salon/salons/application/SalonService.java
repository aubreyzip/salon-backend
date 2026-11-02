package com.salon.salons.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.identity.UserDirectory;
import com.salon.identity.UserSummary;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import com.salon.salons.domain.BranchRepository;
import com.salon.salons.domain.BranchStatus;
import com.salon.salons.domain.Salon;
import com.salon.salons.domain.SalonErrors;
import com.salon.salons.domain.SalonRepository;
import com.salon.salons.domain.Slugs;
import com.salon.salons.domain.StaffMember;
import com.salon.salons.domain.StaffMemberRepository;
import com.salon.salons.domain.StaffStatus;
import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Salon creation, the owner's view of their salons, and submission for review.
 */
@Service
@Transactional
public class SalonService {

	private static final String ENTITY_SALON = "salon";

	private final SalonRepository salons;

	private final BranchRepository branches;

	private final StaffMemberRepository staff;

	private final TenantAccess access;

	private final UserDirectory userDirectory;

	private final AuditLog auditLog;

	private final Clock clock;

	SalonService(SalonRepository salons, BranchRepository branches, StaffMemberRepository staff, TenantAccess access,
			UserDirectory userDirectory, AuditLog auditLog, Clock clock) {
		this.salons = salons;
		this.branches = branches;
		this.staff = staff;
		this.access = access;
		this.userDirectory = userDirectory;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** Creates a salon in {@code DRAFT}; the creator becomes its first owner. */
	public SalonView create(UUID userId, String name, String description) {
		UserSummary user = userDirectory.find(userId)
			.filter(UserSummary::active)
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "Unknown user."));
		Instant now = clock.instant();
		Salon salon = salons.save(new Salon(name.strip(), uniqueSlug(name), blankToNull(description), userId, now));
		staff.save(StaffMember.founder(salon.getId(), userId, user.displayName(), now));
		auditLog.record(new AuditRecord(userId, salon.getId(), "salons.salon.created", ENTITY_SALON,
				salon.getId().toString(), null));
		return SalonView.of(salon);
	}

	@Transactional(readOnly = true)
	public List<MySalonView> mySalons(UUID userId) {
		List<StaffMember> memberships = staff.findByUserIdAndStatus(userId, StaffStatus.ACTIVE);
		Map<UUID, Salon> salonsById = salons
			.findAllById(memberships.stream().map(StaffMember::getSalonId).toList())
			.stream()
			.collect(Collectors.toMap(Salon::getId, Function.identity()));
		return memberships.stream()
			.filter(member -> salonsById.containsKey(member.getSalonId()))
			.map(member -> {
				Salon salon = salonsById.get(member.getSalonId());
				return new MySalonView(salon.getId(), salon.getName(), salon.getSlug(), salon.getStatus(),
						member.getId(), member.getRole(), member.getRole().permissions());
			})
			.sorted(Comparator.comparing(MySalonView::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	@Transactional(readOnly = true)
	public SalonView get(UUID salonId, UUID userId) {
		access.requireMember(salonId, userId);
		return SalonView.of(load(salonId));
	}

	public SalonView update(UUID salonId, UUID userId, String name, String description) {
		access.require(salonId, userId, SalonPermission.SALON_MANAGE);
		Salon salon = load(salonId);
		salon.update(name.strip(), blankToNull(description), clock.instant());
		auditLog.record(new AuditRecord(userId, salonId, "salons.salon.updated", ENTITY_SALON, salonId.toString(),
				null));
		return SalonView.of(salon);
	}

	public SalonView submitForReview(UUID salonId, UUID userId) {
		access.require(salonId, userId, SalonPermission.SALON_MANAGE);
		Salon salon = load(salonId);
		if (!branches.existsBySalonIdAndStatusAndLatitudeIsNotNull(salonId, BranchStatus.ACTIVE)) {
			throw SalonErrors.notReadyForReview();
		}
		salon.submitForReview(clock.instant());
		auditLog.record(new AuditRecord(userId, salonId, "salons.salon.submitted", ENTITY_SALON, salonId.toString(),
				null));
		return SalonView.of(salon);
	}

	private Salon load(UUID salonId) {
		return salons.findById(salonId).orElseThrow(SalonErrors::salonNotFound);
	}

	private String uniqueSlug(String name) {
		String base = Slugs.slugify(name, "salon");
		String candidate = base;
		for (int suffix = 2; salons.existsBySlug(candidate); suffix++) {
			candidate = base + "-" + suffix;
		}
		return candidate;
	}

	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
