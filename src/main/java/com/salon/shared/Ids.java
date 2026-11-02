package com.salon.shared;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 identifiers (RFC 9562) for primary keys.
 */
public final class Ids {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Ids() {
	}

	public static UUID newId() {
		byte[] random = new byte[10];
		RANDOM.nextBytes(random);

		long timestamp = System.currentTimeMillis() & 0xFFFF_FFFF_FFFFL;
		long randA = ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);
		long mostSignificant = (timestamp << 16) | (0x7L << 12) | randA;

		long randB = 0;
		for (int i = 2; i < 10; i++) {
			randB = (randB << 8) | (random[i] & 0xFFL);
		}
		long leastSignificant = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;

		return new UUID(mostSignificant, leastSignificant);
	}

}
