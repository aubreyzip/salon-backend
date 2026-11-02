package com.salon.identity.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByEmail(String normalizedEmail);

	boolean existsByPhoneE164AndPhoneVerifiedAtIsNotNullAndIdNot(String phoneE164, UUID excludedUserId);

}
