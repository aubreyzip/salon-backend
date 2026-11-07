package com.salon.booking.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.salon.booking.application.BookingViews.AttentionView;
import com.salon.booking.application.BookingViews.BranchSummary;
import com.salon.booking.application.BookingViews.ClientView;
import com.salon.booking.application.BookingViews.CustomerAppointmentView;
import com.salon.booking.application.BookingViews.ItemView;
import com.salon.booking.application.BookingViews.SalonAppointmentView;
import com.salon.booking.application.BookingViews.StaffSummary;
import com.salon.booking.domain.Appointment;
import com.salon.booking.domain.AppointmentItem;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.booking.domain.SalonClient;
import com.salon.booking.domain.SalonClientRepository;
import com.salon.identity.UserDirectory;
import com.salon.identity.UserSummary;
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import com.salon.salons.StaffInfo;
import org.springframework.stereotype.Component;

/**
 * Builds the booking views with batched lookups (clients, linked accounts, branches, staff).
 * Callers run inside a transaction and have already checked that the caller may see the data.
 */
@Component
class AppointmentViews {

	private final SalonClientRepository clients;

	private final UserDirectory users;

	private final SalonDirectory directory;

	private final Clock clock;

	AppointmentViews(SalonClientRepository clients, UserDirectory users, SalonDirectory directory, Clock clock) {
		this.clients = clients;
		this.users = users;
		this.directory = directory;
		this.clock = clock;
	}

	List<CustomerAppointmentView> forCustomer(List<Appointment> appointments) {
		Map<UUID, Optional<BranchInfo>> branches = new HashMap<>();
		Map<UUID, Optional<StaffInfo>> staff = new HashMap<>();
		return appointments.stream().map(appointment -> {
			Optional<BranchInfo> branch = branches.computeIfAbsent(appointment.getBranchId(),
					id -> directory.findBranch(appointment.getSalonId(), id));
			Optional<StaffInfo> member = staff.computeIfAbsent(appointment.getStaffId(),
					id -> directory.findStaff(appointment.getSalonId(), id));
			Instant changeableUntil = branch
				.map(info -> appointment.getStartAt().minus(Duration.ofMinutes(info.cancellationCutoffMinutes())))
				.orElse(null);
			return new CustomerAppointmentView(appointment.getId(), appointment.getSalonId(),
					branch.map(info -> new BranchSummary(info.id(), info.name(), info.timeZone().getId())).orElse(null),
					new StaffSummary(appointment.getStaffId(), member.map(StaffInfo::displayName).orElse(null)),
					appointment.getStatus(), appointment.getStartAt(), appointment.getEndAt(), items(appointment),
					total(appointment), currency(appointment), appointment.getCustomerNote(),
					appointment.getStatusReason(), appointment.getCancelledBy(), appointment.getConfirmationDeadline(),
					changeableUntil, appointment.getCreatedAt());
		}).toList();
	}

	List<SalonAppointmentView> forSalon(List<Appointment> appointments) {
		Map<UUID, ClientView> clientViews = clients(
				clients.findAllById(appointments.stream().map(Appointment::getClientId).distinct().toList()));
		Instant now = clock.instant();
		return appointments.stream()
			.map(appointment -> new SalonAppointmentView(appointment.getId(), appointment.getBranchId(),
					appointment.getStaffId(), clientViews.get(appointment.getClientId()), appointment.getStatus(),
					appointment.getSource(), appointment.getStartAt(), appointment.getEndAt(),
					appointment.getBlockEndAt(), items(appointment), total(appointment), currency(appointment),
					appointment.getCustomerNote(), appointment.getInternalNote(), appointment.getStatusReason(),
					appointment.getCancelledBy(), appointment.getConfirmationDeadline(),
					appointment.needsAttention() ? new AttentionView(appointment.getAttentionReason(),
							appointment.getAttentionAt(), appointment.getAttentionByUserId()) : null,
					appointment.getStatus() == AppointmentStatus.CONFIRMED && !now.isBefore(appointment.getStartAt()),
					appointment.getCreatedByUserId(), appointment.getCreatedAt()))
			.toList();
	}

	/** Linked clients show the account's current name and phone (decision 35). */
	Map<UUID, ClientView> clients(Collection<SalonClient> salonClients) {
		Map<UUID, UserSummary> accounts = users
			.findAll(salonClients.stream().map(SalonClient::getUserId).filter(id -> id != null).distinct().toList())
			.stream()
			.collect(Collectors.toMap(UserSummary::id, Function.identity()));
		return salonClients.stream().collect(Collectors.toMap(SalonClient::getId, client -> {
			if (!client.isLinked()) {
				return new ClientView(client.getId(), client.getDisplayName(), client.getPhoneE164(), false,
						client.getCreatedAt());
			}
			UserSummary account = accounts.get(client.getUserId());
			return new ClientView(client.getId(), account != null ? account.displayName() : null,
					account != null ? account.phoneE164() : null, true, client.getCreatedAt());
		}));
	}

	private static List<ItemView> items(Appointment appointment) {
		return appointment.getItems().stream().map(ItemView::of).toList();
	}

	private static BigDecimal total(Appointment appointment) {
		return appointment.getItems().stream().map(AppointmentItem::price).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static String currency(Appointment appointment) {
		return appointment.getItems().getFirst().currency();
	}

}
