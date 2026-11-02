package com.salon.identity.domain;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressesTest {

	@Test
	void normalizesIndependentlyOfTheTurkishDefaultLocale() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			// With the Turkish locale, "I".toLowerCase() would produce the dotless "ı".
			assertThat(EmailAddresses.normalize("  INFO@MAIL.COM ")).isEqualTo("info@mail.com");
		}
		finally {
			Locale.setDefault(original);
		}
	}

}
