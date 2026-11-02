package com.salon.identity.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

	@ParameterizedTest
	@ValueSource(strings = { "+905321234567", "+90 532 123 45 67", "+90 (532) 123-45-67", "0532 123 45 67",
			"05321234567", "532 123 4567", "5321234567", "905321234567", "00905321234567", " 0532.123.45.67 " })
	void normalizesTurkishMobileNumbersToE164(String input) {
		assertThat(PhoneNumbers.normalizeTurkishMobile(input)).contains("+905321234567");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "0212 123 45 67", "+902121234567", "+4915112345678", "0532 123 45", "05321234567890",
			"abc", "+90532123456a" })
	void rejectsLandlinesForeignNumbersAndGarbage(String input) {
		assertThat(PhoneNumbers.normalizeTurkishMobile(input)).isEmpty();
	}

	@Test
	void masksNumbersForLogsAndAuditEntries() {
		assertThat(PhoneNumbers.mask("+905321234567")).isEqualTo("+90532*****67");
	}

}
