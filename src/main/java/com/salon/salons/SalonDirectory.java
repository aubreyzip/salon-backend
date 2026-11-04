package com.salon.salons;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salon.salons.domain.Branch;
import com.salon.salons.domain.BranchRepository;
import com.salon.salons.domain.Salon;
import com.salon.salons.domain.SalonErrors;
import com.salon.salons.domain.SalonRepository;
import com.salon.salons.domain.SalonStatus;
import com.salon.salons.domain.StaffMember;
import com.salon.salons.domain.StaffMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Salon, branch and staff facts for other modules (catalog, scheduling, booking). Tenant-scoped
 * lookups take the salon id; {@link #findPublicBranch} is for public (customer) endpoints.
 * <p>
 * Changes to salon-scoped data in other modules follow the membership-change pattern: lock the
 * salon ({@link #lockSalon}) when a salon-wide invariant is checked, then check access with
 * {@link TenantAccess}, then {@link #ensureModifiable}.
 */
@Service
@Transactional(readOnly = true)
public class SalonDirectory {

	private final SalonRepository salons;

	private final BranchRepository branches;

	private final StaffMemberRepository staff;

	SalonDirectory(SalonRepository salons, BranchRepository branches, StaffMemberRepository staff) {
		this.salons = salons;
		this.branches = branches;
		this.staff = staff;
	}

	/**
	 * Locks the salon row until the caller's transaction ends (see
	 * {@code SalonRepository.findByIdForUpdate}).
	 * @throws com.salon.shared.ApiException {@code 404 SALON_NOT_FOUND} if the salon does not exist
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockSalon(UUID salonId) {
		salons.findByIdForUpdate(salonId).orElseThrow(SalonErrors::salonNotFound);
	}

	/**
	 * @throws com.salon.shared.ApiException {@code 409 SALON_SUSPENDED} if the salon is suspended
	 */
	public void ensureModifiable(UUID salonId) {
		salons.findById(salonId).orElseThrow(SalonErrors::salonNotFound).ensureModifiable();
	}

	public Optional<BranchInfo> findBranch(UUID salonId, UUID branchId) {
		return branches.findByIdAndSalonId(branchId, salonId).map(this::info);
	}

	/** A branch customers may see: active, in an active salon. Anything else is "not found". */
	public Optional<BranchInfo> findPublicBranch(UUID branchId) {
		return branches.findById(branchId).map(this::info).filter(BranchInfo::isPublic);
	}

	public Optional<StaffInfo> findStaff(UUID salonId, UUID staffId) {
		return staff.findByIdAndSalonId(staffId, salonId).map(SalonDirectory::info);
	}

	public List<StaffInfo> listStaff(UUID salonId) {
		return staff.findBySalonIdOrderByDisplayNameAsc(salonId).stream().map(SalonDirectory::info).toList();
	}

	private BranchInfo info(Branch branch) {
		boolean salonActive = salons.findById(branch.getSalonId())
			.map(Salon::getStatus)
			.filter(SalonStatus.ACTIVE::equals)
			.isPresent();
		var policy = branch.getBookingPolicy();
		return new BranchInfo(branch.getId(), branch.getSalonId(), branch.getName(), ZoneId.of(branch.getTimeZone()),
				branch.isActive(), salonActive, policy.slotIntervalMinutes(), policy.minNoticeMinutes(),
				policy.maxAdvanceDays());
	}

	private static StaffInfo info(StaffMember member) {
		return new StaffInfo(member.getId(), member.getSalonId(), member.getDisplayName(), member.isActive(),
				member.isBookable(), member.getBranchIds());
	}

}
