package com.salon.salons.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salon.salons.StaffRole;
import com.salon.shared.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * A person working at a salon, and at the same time that person's membership (role) in the salon.
 * The profile can exist before the person has an account; an accepted invitation links the
 * account ({@code userId}).
 */
@Entity
@Table(name = "staff_member")
public class StaffMember extends BaseEntity {

	private UUID salonId;

	private UUID userId;

	private String displayName;

	private String title;

	private String bio;

	@Enumerated(EnumType.STRING)
	private StaffRole role;

	@Enumerated(EnumType.STRING)
	private StaffStatus status;

	private boolean bookable;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "staff_branch", joinColumns = @JoinColumn(name = "staff_id"))
	private Set<BranchAssignment> branchAssignments = new HashSet<>();

	private Instant createdAt;

	private Instant updatedAt;

	protected StaffMember() {
	}

	public StaffMember(UUID salonId, StaffProfile profile, StaffRole role, Instant now) {
		this.salonId = salonId;
		this.role = role;
		this.status = StaffStatus.ACTIVE;
		this.createdAt = now;
		updateProfile(profile, now);
	}

	/** The salon creator's membership. Owners are not bookable until they choose to be. */
	public static StaffMember founder(UUID salonId, UUID userId, String displayName, Instant now) {
		StaffMember owner = new StaffMember(salonId, new StaffProfile(displayName, null, null, false), StaffRole.OWNER,
				now);
		owner.userId = userId;
		return owner;
	}

	public void updateProfile(StaffProfile profile, Instant now) {
		this.displayName = profile.displayName();
		this.title = profile.title();
		this.bio = profile.bio();
		this.bookable = profile.bookable();
		this.updatedAt = now;
	}

	public void changeRole(StaffRole role, Instant now) {
		this.role = role;
		this.updatedAt = now;
	}

	/** Replaces the branch assignments. Callers must check that the branches belong to this salon. */
	public void assignBranches(Set<UUID> branchIds, Instant now) {
		branchAssignments.clear();
		branchIds.forEach(branchId -> branchAssignments.add(new BranchAssignment(salonId, branchId)));
		this.updatedAt = now;
	}

	public void linkUser(UUID userId, Instant now) {
		if (this.userId != null) {
			throw SalonErrors.staffAlreadyLinked();
		}
		this.userId = userId;
		this.updatedAt = now;
	}

	public void deactivate(Instant now) {
		this.status = StaffStatus.INACTIVE;
		this.updatedAt = now;
	}

	/**
	 * Restores a deactivated member with the same role, branch assignments and account link.
	 * Invitations revoked by the deactivation stay revoked.
	 */
	public void reactivate(Instant now) {
		this.status = StaffStatus.ACTIVE;
		this.updatedAt = now;
	}

	public boolean isActive() {
		return status == StaffStatus.ACTIVE;
	}

	public boolean isLinked() {
		return userId != null;
	}

	public Set<UUID> getBranchIds() {
		return branchAssignments.stream().map(BranchAssignment::branchId).collect(Collectors.toUnmodifiableSet());
	}

	public UUID getSalonId() {
		return salonId;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getTitle() {
		return title;
	}

	public String getBio() {
		return bio;
	}

	public StaffRole getRole() {
		return role;
	}

	public boolean isBookable() {
		return bookable;
	}

}
