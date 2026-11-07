package com.salon.identity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salon.identity.domain.UserAccount;
import com.salon.identity.domain.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the identity module for looking up users.
 */
@Service
@Transactional(readOnly = true)
public class UserDirectory {

	private final UserAccountRepository users;

	UserDirectory(UserAccountRepository users) {
		this.users = users;
	}

	public Optional<UserSummary> find(UUID userId) {
		return users.findById(userId).map(UserDirectory::summary);
	}

	/** The users that exist among the ids, in no particular order. */
	public List<UserSummary> findAll(Collection<UUID> userIds) {
		return userIds.isEmpty() ? List.of() : users.findAllById(userIds).stream().map(UserDirectory::summary).toList();
	}

	private static UserSummary summary(UserAccount user) {
		return new UserSummary(user.getId(), user.getEmail(), user.isEmailVerified(), user.getDisplayName(),
				user.isActive(), user.getPhoneE164(), user.isPhoneVerified());
	}

}
