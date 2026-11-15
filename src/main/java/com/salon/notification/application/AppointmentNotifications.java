package com.salon.notification.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.salon.booking.AppointmentEvent;
import com.salon.booking.AppointmentHistory;
import com.salon.identity.UserDirectory;
import com.salon.identity.UserSummary;
import com.salon.notification.application.AppointmentRecipients.Party;
import com.salon.notification.application.AppointmentRecipients.Target;
import com.salon.notification.domain.Notification;
import com.salon.notification.domain.NotificationKind;
import com.salon.notification.domain.NotificationRepository;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stage 1 of the outbox (ADR-0014, ADR-0011): after the booking transaction commits, creates the
 * in-app notifications for an appointment change, in its own transaction and without network calls.
 * <ul>
 * <li>Content comes from the history entry the event names, never from the current appointment
 * (decision 56). Current state is read only to decide who may still receive it: active accounts
 * and active memberships.</li>
 * <li>Recipients follow decision 44 ({@link AppointmentRecipients}). The member who made a
 * salon-side change is not notified of it; customers get a confirmation of their own actions.</li>
 * <li>Idempotent: a redelivered event finds its notifications and creates nothing (unique source,
 * recipient and kind).</li>
 * </ul>
 * A failure leaves the event publication incomplete, so the registry resubmits it.
 */
@Component
class AppointmentNotifications {

	private static final Logger log = LoggerFactory.getLogger(AppointmentNotifications.class);

	private final AppointmentHistory history;

	private final SalonDirectory salons;

	private final UserDirectory users;

	private final NotificationRepository notifications;

	private final Clock clock;

	AppointmentNotifications(AppointmentHistory history, SalonDirectory salons, UserDirectory users,
			NotificationRepository notifications, Clock clock) {
		this.history = history;
		this.salons = salons;
		this.users = users;
		this.notifications = notifications;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void on(AppointmentEvent event) {
		AppointmentHistory.Change change = history.find(event.historyEntryId())
			.orElseThrow(() -> new IllegalStateException(
					"Appointment history entry " + event.historyEntryId() + " not found"));
		List<Recipient> recipients = recipients(event, change);
		Set<UUID> activeUsers = users.findAll(recipients.stream().map(Recipient::userId).collect(Collectors.toSet()))
			.stream()
			.filter(UserSummary::active)
			.map(UserSummary::id)
			.collect(Collectors.toSet());
		int created = 0;
		for (Recipient recipient : recipients) {
			if (activeUsers.contains(recipient.userId()) && !notifications
				.existsBySourceIdAndUserIdAndKind(change.id(), recipient.userId(), recipient.kind())) {
				notifications.save(Notification.forAppointment(recipient.userId(), recipient.kind(), change.id(),
						event.salonId(), event.branchId(), event.appointmentId(), change.startAt(),
						change.previousStartAt(), change.reason(), clock.instant()));
				created++;
			}
		}
		log.debug("{} for appointment {}: {} notification(s) created", event.getClass().getSimpleName(),
				event.appointmentId(), created);
	}

	private List<Recipient> recipients(AppointmentEvent event, AppointmentHistory.Change change) {
		Set<Recipient> recipients = new LinkedHashSet<>();
		for (Target target : AppointmentRecipients.targets(event, change)) {
			for (UUID userId : users(target.party(), event, change)) {
				// Members are not told about their own salon-side changes; customers get a confirmation.
				if (target.party() == Party.CUSTOMER || !userId.equals(change.actorUserId())) {
					recipients.add(new Recipient(userId, target.kind()));
				}
			}
		}
		return new ArrayList<>(recipients);
	}

	private List<UUID> users(Party party, AppointmentEvent event, AppointmentHistory.Change change) {
		return switch (party) {
			case CUSTOMER -> Optional.ofNullable(event.customerUserId()).stream().toList();
			case STAFF -> member(event.salonId(), change.staffId());
			case PREVIOUS_STAFF -> member(event.salonId(), change.previousStaffId());
			case DESK -> salons.findMembersWith(event.salonId(), event.branchId(), SalonPermission.APPOINTMENT_MANAGE_ANY)
				.stream()
				.map(SalonMembership::userId)
				.toList();
		};
	}

	private List<UUID> member(UUID salonId, UUID staffId) {
		if (staffId == null) {
			return List.of();
		}
		return salons.findActiveMember(salonId, staffId).map(SalonMembership::userId).stream().toList();
	}

	private record Recipient(UUID userId, NotificationKind kind) {
	}

}
