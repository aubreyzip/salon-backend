package com.salon.identity;

import java.util.Optional;
import java.util.UUID;

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
		return users.findById(userId)
			.map(user -> new UserSummary(user.getId(), user.getEmail(), user.isEmailVerified(), user.getDisplayName(),
					user.isActive()));
	}

}
