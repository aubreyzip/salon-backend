package com.salon.booking.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.booking.application.BookingViews.ClientView;
import com.salon.booking.domain.BookingErrors;
import com.salon.booking.domain.SalonClient;
import com.salon.booking.domain.SalonClientRepository;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import com.salon.shared.PhoneNumbers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A salon's clients (ADR-0013 decisions 35 and 36). Reading needs {@code CLIENT_VIEW}; branch-scoped
 * holders (receptionists) see only clients with an appointment in one of their branches. Anyone who
 * may book ({@code APPOINTMENT_MANAGE_ANY} or {@code _OWN}) can add a walk-in client. Platform
 * accounts cannot be searched; linked clients are created by the customer's own booking.
 */
@Service
@Transactional
public class SalonClientService {

	private final SalonClientRepository clients;

	private final AppointmentViews views;

	private final TenantAccess access;

	private final SalonDirectory directory;

	private final AuditLog auditLog;

	private final Clock clock;

	SalonClientService(SalonClientRepository clients, AppointmentViews views, TenantAccess access,
			SalonDirectory directory, AuditLog auditLog, Clock clock) {
		this.clients = clients;
		this.views = views;
		this.access = access;
		this.directory = directory;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Page<ClientView> list(UUID salonId, UUID userId, Pageable pageable) {
		SalonMembership member = access.require(salonId, userId, SalonPermission.CLIENT_VIEW);
		Optional<Set<UUID>> branches = member.branchRestriction();
		if (branches.isPresent() && branches.get().isEmpty()) {
			return Page.empty(pageable);
		}
		Page<SalonClient> page = branches.isEmpty() ? clients.findBySalonId(salonId, pageable)
				: clients.findBySalonIdWithAppointmentsIn(salonId, branches.get(), pageable);
		var byId = views.clients(page.getContent());
		return new PageImpl<>(page.getContent().stream().map(client -> byId.get(client.getId())).toList(),
				page.getPageable(), page.getTotalElements());
	}

	@Transactional(readOnly = true)
	public ClientView get(UUID salonId, UUID clientId, UUID userId) {
		SalonMembership member = access.require(salonId, userId, SalonPermission.CLIENT_VIEW);
		SalonClient client = clients.findByIdAndSalonId(clientId, salonId)
			.filter(found -> member.branchRestriction()
				.map(branches -> !branches.isEmpty() && clients.hasAppointmentsIn(salonId, found.getId(), branches))
				.orElse(true))
			.orElseThrow(BookingErrors::clientNotFound);
		return views.clients(List.of(client)).get(client.getId());
	}

	/**
	 * A walk-in or phone-booking client. The phone is optional and not unique (families share).
	 * @throws com.salon.shared.ApiException {@code 400 INVALID_PHONE_NUMBER}
	 */
	public ClientView create(UUID salonId, UUID userId, String displayName, String phone) {
		access.requireAny(salonId, userId, SalonPermission.APPOINTMENT_MANAGE_ANY,
				SalonPermission.APPOINTMENT_MANAGE_OWN);
		directory.ensureModifiable(salonId);
		String e164 = phone == null || phone.isBlank() ? null
				: PhoneNumbers.normalizeTurkishMobile(phone).orElseThrow(BookingErrors::invalidPhoneNumber);
		SalonClient client = clients.save(SalonClient.walkIn(salonId, displayName, e164, userId, clock.instant()));
		// No name or phone in the audit log (KVKK).
		auditLog.record(new AuditRecord(userId, salonId, "booking.client.created", "salon_client",
				client.getId().toString(), null));
		return views.clients(List.of(client)).get(client.getId());
	}

}
