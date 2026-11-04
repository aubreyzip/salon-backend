package com.salon.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The editable fields of a service.
 *
 * @param sectionId optional salon-defined section
 * @param bufferMinutes cleanup time after the service, blocked in the staff member's calendar
 * @param priceFrom the price is a starting price ("from ₺500"), the final price may be higher
 * @param onlineBookable customers may book it online; otherwise only the salon books it
 */
public record ServiceDetails(int categoryId, UUID sectionId, String name, String description, int durationMinutes,
		int bufferMinutes, BigDecimal price, boolean priceFrom, boolean onlineBookable) {
}
