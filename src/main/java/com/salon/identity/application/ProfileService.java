package com.salon.identity.application;

import java.time.Clock;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.identity.domain.ChallengeChannel;
import com.salon.identity.domain.ChallengePurpose;
import com.salon.shared.PhoneNumbers;
import com.salon.identity.domain.PlatformRole;
import com.salon.identity.domain.UserAccount;
import com.salon.identity.domain.UserAccountRepository;
import com.salon.identity.domain.VerificationChallenge;
import com.salon.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's own profile and phone verification (ADR-0007).
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class ProfileService {

	private final UserAccountRepository users;

	private final VerificationService verification;

	private final AuditLog auditLog;

	private final Clock clock;

	ProfileService(UserAccountRepository users, VerificationService verification, AuditLog auditLog, Clock clock) {
		this.users = users;
		this.verification = verification;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public UserProfile get(UUID userId) {
		UserAccount user = users.findById(userId).orElseThrow(IdentityErrors::userNotFound);
		return new UserProfile(user.getId(), user.getEmail(), user.isEmailVerified(), user.getPhoneE164(),
				user.isPhoneVerified(), user.getDisplayName(),
				user.getPlatformRoles().stream().map(PlatformRole::name).collect(Collectors.toSet()));
	}

	public void requestPhoneVerification(UUID userId, String phone) {
		String e164 = PhoneNumbers.normalizeTurkishMobile(phone).orElseThrow(IdentityErrors::invalidPhoneNumber);
		UserAccount user = users.findById(userId).orElseThrow(IdentityErrors::userNotFound);
		if (user.isPhoneVerified() && e164.equals(user.getPhoneE164())) {
			throw IdentityErrors.phoneAlreadyVerified();
		}
		if (users.existsByPhoneE164AndPhoneVerifiedAtIsNotNullAndIdNot(e164, userId)) {
			throw IdentityErrors.phoneAlreadyInUse();
		}
		verification.issue(user, ChallengePurpose.PHONE_VERIFICATION, ChallengeChannel.SMS, e164);
	}

	public void confirmPhone(UUID userId, String code) {
		UserAccount user = users.findById(userId).orElseThrow(IdentityErrors::userNotFound);
		VerificationChallenge challenge = verification.verify(userId, ChallengePurpose.PHONE_VERIFICATION, code)
			.orElseThrow(IdentityErrors::invalidOrExpiredCode);
		String e164 = challenge.getTarget();
		if (users.existsByPhoneE164AndPhoneVerifiedAtIsNotNullAndIdNot(e164, userId)) {
			throw IdentityErrors.phoneAlreadyInUse();
		}
		user.verifyPhone(e164, clock.instant());
		auditLog.record(new AuditRecord(userId, null, "identity.phone.verified", "user", userId.toString(),
				PhoneNumbers.mask(e164)));
	}

}
