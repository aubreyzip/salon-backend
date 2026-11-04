package com.salon.scheduling.domain;

import java.time.DayOfWeek;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores {@link DayOfWeek} as its ISO number (1 = Monday ... 7 = Sunday) in a {@code smallint}
 * column (ADR-0006).
 */
@Converter(autoApply = true)
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Short> {

	@Override
	public Short convertToDatabaseColumn(DayOfWeek day) {
		return day == null ? null : (short) day.getValue();
	}

	@Override
	public DayOfWeek convertToEntityAttribute(Short value) {
		return value == null ? null : DayOfWeek.of(value);
	}

}
