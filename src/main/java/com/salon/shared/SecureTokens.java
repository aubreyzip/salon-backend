package com.salon.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generation and hashing of secrets that are stored only as hashes (refresh tokens, invitation tokens,
 * one-time codes).
 */
public final class SecureTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private SecureTokens() {
	}

	/** A 256-bit random, URL-safe token (refresh tokens, invitation tokens). */
	public static String newUrlSafeToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** A random numeric one-time code with the given number of digits (leading zeros allowed). */
	public static String newNumericCode(int digits) {
		StringBuilder code = new StringBuilder(digits);
		for (int i = 0; i < digits; i++) {
			code.append(RANDOM.nextInt(10));
		}
		return code.toString();
	}

	public static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	/** Hashes a one-time code bound to its challenge id, so equal codes never produce equal hashes. */
	public static String hashOtp(UUID challengeId, String code) {
		return sha256(challengeId + ":" + code);
	}

	public static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

}
