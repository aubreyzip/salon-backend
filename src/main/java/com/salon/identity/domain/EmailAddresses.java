package com.salon.identity.domain;

import java.util.Locale;

public final class EmailAddresses {

	private EmailAddresses() {
	}

	/**
	 * Trims and lower-cases an email address. Uses {@link Locale#ROOT}: under a Turkish default
	 * locale {@code "I".toLowerCase()} yields a dotless {@code "ı"}, which would break lookups.
	 */
	public static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

}
