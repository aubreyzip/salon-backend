package com.salon.shared;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

	@Test
	void generatesVersion7Rfc9562Uuids() {
		UUID id = Ids.newId();

		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void embedsTheCurrentTimeSoIdsAreTimeOrdered() throws InterruptedException {
		long before = System.currentTimeMillis();
		UUID first = Ids.newId();
		Thread.sleep(2);
		UUID second = Ids.newId();
		long after = System.currentTimeMillis();

		long firstMillis = first.getMostSignificantBits() >>> 16;
		assertThat(firstMillis).isBetween(before, after);
		assertThat(second.getMostSignificantBits() >>> 16).isGreaterThan(firstMillis);
		assertThat(Long.compareUnsigned(first.getMostSignificantBits(), second.getMostSignificantBits())).isNegative();
	}

	@Test
	void doesNotRepeat() {
		Set<UUID> ids = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			ids.add(Ids.newId());
		}
		assertThat(ids).hasSize(10_000);
	}

}
