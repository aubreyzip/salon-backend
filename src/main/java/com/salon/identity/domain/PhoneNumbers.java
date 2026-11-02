package com.salon.identity.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalizes user-entered phone numbers to E.164. Only Turkish mobile numbers ({@code +905XXXXXXXXX})
 * are accepted for now (ADR-0007).
 */
public final class PhoneNumbers {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");

	private static final Pattern TURKISH_MOBILE = Pattern.compile("\\+905\\d{9}");

	private PhoneNumbers() {
	}

	/**
	 * Accepts common local formats such as {@code 0532 123 45 67}, {@code 532-123-4567},
	 * {@code 90 532 123 4567}, {@code 0090...} and {@code +90 (532) 123 45 67}.
	 */
	public static Optional<String> normalizeTurkishMobile(String input) {
		if (input == null) {
			return Optional.empty();
		}
		String digits = SEPARATORS.matcher(input.strip()).replaceAll("");
		String e164;
		if (digits.startsWith("+")) {
			e164 = digits;
		}
		else if (digits.startsWith("00")) {
			e164 = "+" + digits.substring(2);
		}
		else if (digits.startsWith("90") && digits.length() == 12) {
			e164 = "+" + digits;
		}
		else if (digits.startsWith("0") && digits.length() == 11) {
			e164 = "+90" + digits.substring(1);
		}
		else if (digits.length() == 10) {
			e164 = "+90" + digits;
		}
		else {
			return Optional.empty();
		}
		return TURKISH_MOBILE.matcher(e164).matches() ? Optional.of(e164) : Optional.empty();
	}

	/** Masks a phone number for display and logs, e.g. {@code +90532*****67}. */
	public static String mask(String e164) {
		if (e164 == null || e164.length() < 8) {
			return "***";
		}
		return e164.substring(0, 6) + "*".repeat(e164.length() - 8) + e164.substring(e164.length() - 2);
	}

}
