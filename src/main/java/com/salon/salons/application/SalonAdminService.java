package com.salon.salons.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.salons.domain.Salon;
import com.salon.salons.domain.SalonErrors;
import com.salon.salons.domain.SalonRepository;
import com.salon.salons.domain.SalonStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform administration of salons: review queue, approval, rejection and suspension.
 * Access is restricted to {@code PLATFORM_ADMIN} by the {@code /api/v1/admin/**} security rule.
 */
@Service
@Transactional
public class SalonAdminService {

	private final SalonRepository salons;

	private final AuditLog auditLog;

	private final Clock clock;

	SalonAdminService(SalonRepository salons, AuditLog auditLog, Clock clock) {
		this.salons = salons;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Page<SalonView> list(SalonStatus status, Pageable pageable) {
		Page<Salon> page = status == null ? salons.findAll(pageable) : salons.findByStatus(status, pageable);
		return page.map(SalonView::of);
	}

	public SalonView approve(UUID salonId, UUID adminUserId) {
		return transition(salonId, adminUserId, "salons.salon.approved", null, (salon, now) -> salon.approve(now));
	}

	public SalonView reject(UUID salonId, UUID adminUserId, String note) {
		return transition(salonId, adminUserId, "salons.salon.rejected", note,
				(salon, now) -> salon.reject(note, now));
	}

	public SalonView suspend(UUID salonId, UUID adminUserId, String note) {
		return transition(salonId, adminUserId, "salons.salon.suspended", note,
				(salon, now) -> salon.suspend(note, now));
	}

	public SalonView reactivate(UUID salonId, UUID adminUserId) {
		return transition(salonId, adminUserId, "salons.salon.reactivated", null,
				(salon, now) -> salon.reactivate(now));
	}

	private SalonView transition(UUID salonId, UUID adminUserId, String action, String note,
			BiConsumer<Salon, Instant> change) {
		Salon salon = salons.findById(salonId).orElseThrow(SalonErrors::salonNotFound);
		change.accept(salon, clock.instant());
		auditLog.record(new AuditRecord(adminUserId, salonId, action, "salon", salonId.toString(), note));
		return SalonView.of(salon);
	}

}
