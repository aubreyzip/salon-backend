package com.salon.salons.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * URL slugs from names, with Turkish characters transliterated ("Güzel Saç Stüdyosu" →
 * "guzel-sac-studyosu").
 */
public final class Slugs {

	private static final int MAX_LENGTH = 60;

	private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

	private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

	private Slugs() {
	}

	public static String slugify(String name, String fallback) {
		// "ı" (dotless i) has no decomposition, so map it explicitly; NFD handles ç, ğ, ö, ş, ü, İ, â.
		String text = name.replace('ı', 'i');
		text = COMBINING_MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
		text = NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("-");
		text = trimDashes(text);
		if (text.length() > MAX_LENGTH) {
			text = trimDashes(text.substring(0, MAX_LENGTH));
		}
		return text.isEmpty() ? fallback : text;
	}

	private static String trimDashes(String text) {
		int start = 0;
		int end = text.length();
		while (start < end && text.charAt(start) == '-') {
			start++;
		}
		while (end > start && text.charAt(end - 1) == '-') {
			end--;
		}
		return text.substring(start, end);
	}

}
