package com.salon.salons.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.identity.UserDirectory;
import com.salon.identity.UserSummary;
import com.salon.messaging.EmailGateway;
import com.salon.messaging.EmailMessage;
import com.salon.salons.domain.Salon;
import com.salon.salons.domain.SalonErrors;
import com.salon.salons.domain.SalonRepository;
import com.salon.salons.domain.StaffInvitation;
import com.salon.salons.domain.StaffInvitationRepository;
import com.salon.salons.domain.StaffMember;
import com.salon.salons.domain.StaffMemberRepository;
import com.salon.shared.EmailAddresses;
import com.salon.shared.SecureTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations that link a user account to a staff member. The invitation is bound to an email
 * address: only the account with that (verified) email can accept it.
 */
@Service
@Transactional
public class InvitationService {

	static final Duration INVITATION_TTL = Duration.ofDays(7);

	private final StaffInvitationRepository invitations;

	private final StaffMemberRepository staff;

	private final SalonRepository salons;

	private final UserDirectory userDirectory;

	private final EmailGateway emailGateway;

	private final AuditLog auditLog;

	private final Clock clock;

	InvitationService(StaffInvitationRepository invitations, StaffMemberRepository staff, SalonRepository salons,
			UserDirectory userDirectory, EmailGateway emailGateway, AuditLog auditLog, Clock clock) {
		this.invitations = invitations;
		this.staff = staff;
		this.salons = salons;
		this.userDirectory = userDirectory;
		this.emailGateway = emailGateway;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** Replaces any open invitation for the staff member and emails a new token. */
	void invite(Salon salon, StaffMember member, String email, UUID invitedByUserId, Instant now) {
		invitations.findBySalonIdAndStaffIdAndAcceptedAtIsNullAndRevokedAtIsNull(salon.getId(), member.getId())
			.forEach(previous -> previous.revoke(now));

		String normalizedEmail = EmailAddresses.normalize(email);
		String token = SecureTokens.newUrlSafeToken();
		invitations.save(new StaffInvitation(salon.getId(), member.getId(), normalizedEmail,
				SecureTokens.sha256(token), invitedByUserId, now, now.plus(INVITATION_TTL)));
		emailGateway.send(new EmailMessage(normalizedEmail, "You are invited to join " + salon.getName(),
				"You have been invited to join " + salon.getName() + " on Salon as " + member.getDisplayName()
						+ ". Sign in to the Salon app with this email address and enter this invitation code: "
						+ token + "\nThe invitation expires in " + INVITATION_TTL.toDays() + " days."));
		auditLog.record(new AuditRecord(invitedByUserId, salon.getId(), "salons.staff.invited", "staff_member",
				member.getId().toString(), null));
	}

	public MySalonView accept(UUID userId, String token) {
		Instant now = clock.instant();
		StaffInvitation invitation = invitations.findByTokenHash(SecureTokens.sha256(token))
			.filter(candidate -> candidate.isOpen(now))
			.orElseThrow(SalonErrors::invitationInvalid);
		UserSummary user = userDirectory.find(userId)
			.filter(candidate -> candidate.active() && candidate.emailVerified())
			.orElseThrow(SalonErrors::invitationInvalid);
		if (!user.email().equals(invitation.getEmail())) {
			throw SalonErrors.invitationEmailMismatch();
		}

		UUID salonId = invitation.getSalonId();
		// Same lock as staff changes: membership changes in one salon run one at a time.
		Salon salon = salons.findByIdForUpdate(salonId).orElseThrow(SalonErrors::invitationInvalid);
		salon.ensureModifiable();
		StaffMember member = staff.findByIdAndSalonId(invitation.getStaffId(), salonId)
			.filter(StaffMember::isActive)
			.orElseThrow(SalonErrors::invitationInvalid);
		// One membership row per user and salon: former members are reactivated, not re-linked.
		staff.findBySalonIdAndUserId(salonId, userId).ifPresent(existing -> {
			throw existing.isActive() ? SalonErrors.alreadyAMember() : SalonErrors.formerMember();
		});
		member.linkUser(userId, now);
		invitation.accept(userId, now);
		auditLog.record(new AuditRecord(userId, salonId, "salons.invitation.accepted", "staff_member",
				member.getId().toString(), null));

		return new MySalonView(salonId, salon.getName(), salon.getSlug(), salon.getStatus(), member.getId(),
				member.getRole(), member.getRole().permissions());
	}

}
