package com.salon.salons.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import com.salon.salons.StaffRole;
import com.salon.salons.TenantAccess;
import com.salon.salons.domain.BranchRepository;
import com.salon.salons.domain.Salon;
import com.salon.salons.domain.SalonErrors;
import com.salon.salons.domain.SalonRepository;
import com.salon.salons.domain.StaffInvitation;
import com.salon.salons.domain.StaffInvitationRepository;
import com.salon.salons.domain.StaffMember;
import com.salon.salons.domain.StaffMemberRepository;
import com.salon.salons.domain.StaffProfile;
import com.salon.salons.domain.StaffStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff members and their roles.
 * <p>
 * Rules: owners may manage anyone. Other members with {@code STAFF_MANAGE} may only manage members
 * ranked below them and only assign roles ranked below them; they may edit their own profile but
 * not their own role. A salon always keeps at least one active owner linked to an account.
 */
@Service
@Transactional
public class StaffService {

	private static final String ENTITY_STAFF = "staff_member";

	private final StaffMemberRepository staff;

	private final BranchRepository branches;

	private final SalonRepository salons;

	private final StaffInvitationRepository invitations;

	private final InvitationService invitationService;

	private final TenantAccess access;

	private final AuditLog auditLog;

	private final Clock clock;

	StaffService(StaffMemberRepository staff, BranchRepository branches, SalonRepository salons,
			StaffInvitationRepository invitations, InvitationService invitationService, TenantAccess access,
			AuditLog auditLog, Clock clock) {
		this.staff = staff;
		this.branches = branches;
		this.salons = salons;
		this.invitations = invitations;
		this.invitationService = invitationService;
		this.access = access;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<StaffView> list(UUID salonId, UUID userId) {
		access.requireMember(salonId, userId);
		Set<UUID> pendingInvitations = invitations
			.findBySalonIdAndAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(salonId, clock.instant())
			.stream()
			.map(StaffInvitation::getStaffId)
			.collect(Collectors.toSet());
		return staff.findBySalonIdOrderByDisplayNameAsc(salonId)
			.stream()
			.map(member -> view(member, pendingInvitations.contains(member.getId())))
			.toList();
	}

	/**
	 * @param inviteEmail if given, an invitation to link an account is sent to this address
	 */
	public StaffView create(UUID salonId, UUID userId, StaffProfile profile, StaffRole role, Set<UUID> branchIds,
			String inviteEmail) {
		Salon salon = lockSalon(salonId);
		SalonMembership actor = access.require(salonId, userId, SalonPermission.STAFF_MANAGE);
		salon.ensureModifiable();
		checkMayManage(actor, null, role);
		checkBranches(salonId, branchIds);

		Instant now = clock.instant();
		StaffMember member = new StaffMember(salonId, profile, role, now);
		member.assignBranches(branchIds, now);
		staff.save(member);
		audit(userId, member, "salons.staff.created");

		boolean invited = false;
		if (inviteEmail != null && !inviteEmail.isBlank()) {
			invitationService.invite(salon, member, inviteEmail, userId, now);
			invited = true;
		}
		return view(member, invited);
	}

	public StaffView update(UUID salonId, UUID staffId, UUID userId, StaffProfile profile, StaffRole role,
			Set<UUID> branchIds) {
		Salon salon = lockSalon(salonId);
		SalonMembership actor = access.require(salonId, userId, SalonPermission.STAFF_MANAGE);
		salon.ensureModifiable();
		StaffMember member = load(salonId, staffId);
		checkMayManage(actor, member, role);
		if (member.getRole() == StaffRole.OWNER && role != StaffRole.OWNER) {
			ensureAnotherOwner(member);
		}
		checkBranches(salonId, branchIds);

		Instant now = clock.instant();
		member.updateProfile(profile, now);
		member.changeRole(role, now);
		member.assignBranches(branchIds, now);
		audit(userId, member, "salons.staff.updated");
		return view(member, hasPendingInvitation(salonId, staffId));
	}

	public StaffView deactivate(UUID salonId, UUID staffId, UUID userId) {
		Salon salon = lockSalon(salonId);
		SalonMembership actor = access.require(salonId, userId, SalonPermission.STAFF_MANAGE);
		salon.ensureModifiable();
		StaffMember member = load(salonId, staffId);
		checkMayManage(actor, member, member.getRole());
		if (member.getRole() == StaffRole.OWNER) {
			ensureAnotherOwner(member);
		}
		Instant now = clock.instant();
		member.deactivate(now);
		invitations.findBySalonIdAndStaffIdAndAcceptedAtIsNullAndRevokedAtIsNull(salonId, staffId)
			.forEach(invitation -> invitation.revoke(now));
		audit(userId, member, "salons.staff.deactivated");
		return view(member, false);
	}

	/**
	 * Restores a deactivated member with their previous role, branch assignments and account link.
	 * The same rank rules as for deactivation apply; reactivating an active member changes nothing.
	 */
	public StaffView reactivate(UUID salonId, UUID staffId, UUID userId) {
		Salon salon = lockSalon(salonId);
		SalonMembership actor = access.require(salonId, userId, SalonPermission.STAFF_MANAGE);
		salon.ensureModifiable();
		StaffMember member = load(salonId, staffId);
		checkMayManage(actor, member, member.getRole());
		if (!member.isActive()) {
			member.reactivate(clock.instant());
			audit(userId, member, "salons.staff.reactivated");
		}
		return view(member, hasPendingInvitation(salonId, staffId));
	}

	/**
	 * Cancels the open invitation of a staff member without changing the staff member: the token
	 * stops working immediately. Does nothing if no invitation is open.
	 */
	public void cancelInvitations(UUID salonId, UUID staffId, UUID userId) {
		Salon salon = lockSalon(salonId);
		SalonMembership actor = access.require(salonId, userId, SalonPermission.STAFF_MANAGE);
		salon.ensureModifiable();
		StaffMember member = load(salonId, staffId);
		checkMayManage(actor, member, member.getRole());
		Instant now = clock.instant();
		List<StaffInvitation> open = invitations
			.findBySalonIdAndStaffIdAndAcceptedAtIsNullAndRevokedAtIsNull(salonId, staffId);
		open.forEach(invitation -> invitation.revoke(now));
		if (!open.isEmpty()) {
			audit(userId, member, "salons.staff.invitation_cancelled");
		}
	}

	public void invite(UUID salonId, UUID staffId, UUID userId, String email) {
		Salon salon = lockSalon(salonId);
		SalonMembership actor = access.require(salonId, userId, SalonPermission.STAFF_MANAGE);
		salon.ensureModifiable();
		StaffMember member = load(salonId, staffId);
		checkMayManage(actor, member, member.getRole());
		if (member.isLinked()) {
			throw SalonErrors.staffAlreadyLinked();
		}
		if (!member.isActive()) {
			throw SalonErrors.staffInactive();
		}
		invitationService.invite(salon, member, email, userId, clock.instant());
	}

	private static void checkMayManage(SalonMembership actor, StaffMember target, StaffRole newRole) {
		if (actor.role() == StaffRole.OWNER) {
			return;
		}
		boolean self = target != null && target.getId().equals(actor.staffId());
		if (self) {
			if (newRole != target.getRole()) {
				throw SalonErrors.roleChangeNotAllowed();
			}
			return;
		}
		if ((target != null && !actor.role().outranks(target.getRole())) || !actor.role().outranks(newRole)) {
			throw SalonErrors.roleChangeNotAllowed();
		}
	}

	private void ensureAnotherOwner(StaffMember owner) {
		boolean countsAsOwner = owner.isActive() && owner.isLinked();
		long activeOwners = staff.countBySalonIdAndRoleAndStatusAndUserIdIsNotNull(owner.getSalonId(),
				StaffRole.OWNER, StaffStatus.ACTIVE);
		if (countsAsOwner && activeOwners <= 1) {
			throw SalonErrors.lastOwner();
		}
	}

	private void checkBranches(UUID salonId, Set<UUID> branchIds) {
		if (!branchIds.isEmpty() && branches.findBySalonIdAndIdIn(salonId, branchIds).size() != branchIds.size()) {
			throw SalonErrors.branchNotInSalon();
		}
	}

	/**
	 * Locks the salon row before anything else is read, so membership changes in one salon run one at
	 * a time and every check (the caller's role, the owner count) sees the latest committed state.
	 * Without the lock, two owners demoting themselves concurrently could both pass the "another
	 * owner remains" check and leave the salon without an owner. Unknown salons give the same
	 * {@code SALON_NOT_FOUND} as salons the caller is not a member of. Callers check membership
	 * before {@link Salon#ensureModifiable()}, so outsiders cannot learn a salon's status.
	 */
	private Salon lockSalon(UUID salonId) {
		return salons.findByIdForUpdate(salonId).orElseThrow(SalonErrors::salonNotFound);
	}

	private StaffMember load(UUID salonId, UUID staffId) {
		return staff.findByIdAndSalonId(staffId, salonId).orElseThrow(SalonErrors::staffNotFound);
	}

	private boolean hasPendingInvitation(UUID salonId, UUID staffId) {
		Instant now = clock.instant();
		return invitations.findBySalonIdAndStaffIdAndAcceptedAtIsNullAndRevokedAtIsNull(salonId, staffId)
			.stream()
			.anyMatch(invitation -> invitation.isOpen(now));
	}

	private void audit(UUID userId, StaffMember member, String action) {
		auditLog.record(new AuditRecord(userId, member.getSalonId(), action, ENTITY_STAFF, member.getId().toString(),
				member.getRole().name()));
	}

	static StaffView view(StaffMember member, boolean invitationPending) {
		return new StaffView(member.getId(), member.getDisplayName(), member.getTitle(), member.getBio(),
				member.getRole(), member.isActive(), member.isBookable(), member.getBranchIds(), member.isLinked(),
				invitationPending);
	}

}
