package com.salon.salons.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			Güzel Saç Stüdyosu       | guzel-sac-studyosu
			IŞIK Kuaför              | isik-kuafor
			İzmir Çiğdem Güzellik    | izmir-cigdem-guzellik
			Barber & Co. — Kadıköy!  | barber-co-kadikoy
			  --Ömer'in Yeri--       | omer-in-yeri
			Hakkâri                  | hakkari
			""")
	void transliteratesTurkishCharacters(String name, String expected) {
		assertThat(Slugs.slugify(name, "salon")).isEqualTo(expected);
	}

	@Test
	void fallsBackWhenNothingIsLeft() {
		assertThat(Slugs.slugify("!!! ???", "salon")).isEqualTo("salon");
	}

	@Test
	void limitsTheLengthWithoutTrailingDashes() {
		String slug = Slugs.slugify("a".repeat(59) + " bbbbbbbb", "salon");

		assertThat(slug).hasSizeLessThanOrEqualTo(60).doesNotEndWith("-");
	}

}
