package com.salon.salons.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import com.salon.salons.domain.BookingPolicy;
import com.salon.salons.domain.Branch;
import com.salon.salons.domain.BranchDetails;
import com.salon.salons.domain.BranchRepository;
import com.salon.salons.domain.Province;
import com.salon.salons.domain.ProvinceRepository;
import com.salon.salons.domain.SalonErrors;
import com.salon.salons.domain.SalonRepository;
import com.salon.salons.domain.Slugs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BranchService {

	private static final String ENTITY_BRANCH = "branch";

	private final BranchRepository branches;

	private final SalonRepository salons;

	private final ProvinceRepository provinces;

	private final TenantAccess access;

	private final AuditLog auditLog;

	private final Clock clock;

	BranchService(BranchRepository branches, SalonRepository salons, ProvinceRepository provinces,
			TenantAccess access, AuditLog auditLog, Clock clock) {
		this.branches = branches;
		this.salons = salons;
		this.provinces = provinces;
		this.access = access;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * @param bookingPolicy {@code null} for the defaults
	 */
	public BranchView create(UUID salonId, UUID userId, BranchDetails details, BookingPolicy bookingPolicy) {
		access.require(salonId, userId, SalonPermission.BRANCH_MANAGE);
		ensureSalonModifiable(salonId);
		checkProvince(details);
		Branch branch = branches.save(new Branch(salonId, uniqueSlug(salonId, details.name()), details,
				bookingPolicy != null ? bookingPolicy : BookingPolicy.defaults(), clock.instant()));
		audit(userId, branch, "salons.branch.created");
		return view(branch, provinceNames());
	}

	@Transactional(readOnly = true)
	public List<BranchView> list(UUID salonId, UUID userId) {
		access.requireMember(salonId, userId);
		Map<Integer, String> provinceNames = provinceNames();
		return branches.findBySalonIdOrderByNameAsc(salonId).stream().map(b -> view(b, provinceNames)).toList();
	}

	@Transactional(readOnly = true)
	public BranchView get(UUID salonId, UUID branchId, UUID userId) {
		access.requireMember(salonId, userId);
		return view(load(salonId, branchId), provinceNames());
	}

	/**
	 * @param bookingPolicy {@code null} keeps the current policy
	 */
	public BranchView update(UUID salonId, UUID branchId, UUID userId, BranchDetails details,
			BookingPolicy bookingPolicy) {
		return change(salonId, branchId, userId, "salons.branch.updated", (branch, now) -> {
			checkProvince(details);
			branch.update(details, now);
			if (bookingPolicy != null) {
				branch.changeBookingPolicy(bookingPolicy, now);
			}
		});
	}

	public BranchView activate(UUID salonId, UUID branchId, UUID userId) {
		return change(salonId, branchId, userId, "salons.branch.activated", Branch::activate);
	}

	public BranchView deactivate(UUID salonId, UUID branchId, UUID userId) {
		return change(salonId, branchId, userId, "salons.branch.deactivated", Branch::deactivate);
	}

	private BranchView change(UUID salonId, UUID branchId, UUID userId, String action,
			BiConsumer<Branch, Instant> change) {
		access.require(salonId, userId, SalonPermission.BRANCH_MANAGE);
		ensureSalonModifiable(salonId);
		Branch branch = load(salonId, branchId);
		change.accept(branch, clock.instant());
		audit(userId, branch, action);
		return view(branch, provinceNames());
	}

	private Branch load(UUID salonId, UUID branchId) {
		return branches.findByIdAndSalonId(branchId, salonId).orElseThrow(SalonErrors::branchNotFound);
	}

	private void ensureSalonModifiable(UUID salonId) {
		salons.findById(salonId).orElseThrow(SalonErrors::salonNotFound).ensureModifiable();
	}

	private void checkProvince(BranchDetails details) {
		if (!provinces.existsById(details.address().provinceId())) {
			throw SalonErrors.unknownProvince();
		}
	}

	private String uniqueSlug(UUID salonId, String name) {
		String base = Slugs.slugify(name, "branch");
		String candidate = base;
		for (int suffix = 2; branches.existsBySalonIdAndSlug(salonId, candidate); suffix++) {
			candidate = base + "-" + suffix;
		}
		return candidate;
	}

	private void audit(UUID userId, Branch branch, String action) {
		auditLog.record(new AuditRecord(userId, branch.getSalonId(), action, ENTITY_BRANCH, branch.getId().toString(),
				null));
	}

	private Map<Integer, String> provinceNames() {
		return provinces.findAll().stream().collect(Collectors.toMap(Province::getId, Province::getName));
	}

	private static BranchView view(Branch branch, Map<Integer, String> provinceNames) {
		var address = branch.getAddress();
		return new BranchView(branch.getId(), branch.getSalonId(), branch.getName(), branch.getSlug(),
				branch.getPhone(),
				new BranchView.AddressView(address.provinceId(), provinceNames.get(address.provinceId()),
						address.district(), address.neighbourhood(), address.addressLine(), address.postalCode()),
				branch.getLatitude(), branch.getLongitude(), branch.getTimeZone(), branch.isActive(),
				branch.getBookingPolicy());
	}

}
