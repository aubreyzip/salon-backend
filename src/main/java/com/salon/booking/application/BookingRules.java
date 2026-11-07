package com.salon.booking.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salon.booking.application.BookingExecutor.Attempt;
import com.salon.booking.domain.Appointment;
import com.salon.booking.domain.AppointmentItem;
import com.salon.booking.domain.AppointmentRepository;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.booking.domain.BookingErrors;
import com.salon.catalog.BookingRequirements;
import com.salon.catalog.ServiceCatalog;
import com.salon.salons.BranchInfo;
import com.salon.scheduling.BookableSlots;
import com.salon.scheduling.BookableSlots.Booker;
import com.salon.scheduling.BookableSlots.FreeStaff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Steps shared by customer and salon bookings and reschedules (ADR-0013). Callers hold the
 * customer lock (if any) and the salon row lock in share mode.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class BookingRules {

	/** Active future appointments per account-linked customer (ADR-0012 decision 24). */
	static final int CUSTOMER_LIMIT = 5;

	private static final UUID NO_APPOINTMENT = new UUID(0, 0);

	private final ServiceCatalog catalog;

	private final BookableSlots slots;

	private final AppointmentRepository appointments;

	private final Clock clock;

	BookingRules(ServiceCatalog catalog, BookableSlots slots, AppointmentRepository appointments, Clock clock) {
		this.catalog = catalog;
		this.slots = slots;
		this.appointments = appointments;
		this.clock = clock;
	}

	/**
	 * @param onlineBookableOnly customer bookings; salon bookings may book every offered service
	 * @throws com.salon.shared.ApiException {@code 400 DUPLICATE_SERVICE}, {@code 400 SERVICE_NOT_OFFERED}
	 */
	BookingRequirements requirements(BranchInfo branch, List<UUID> serviceIds, boolean onlineBookableOnly) {
		if (new HashSet<>(serviceIds).size() != serviceIds.size()) {
			throw BookingErrors.duplicateService();
		}
		return catalog.requirements(branch.salonId(), branch.id(), serviceIds, onlineBookableOnly);
	}

	/**
	 * Decision 28: the requested staff member if free; otherwise ("any") the free candidate with the
	 * fewest booked minutes that local day at the branch, ties to the lowest staff id. Candidates
	 * come from {@link BookableSlots}: qualified for every service and free for the exact start,
	 * buffer included.
	 * @throws com.salon.shared.ApiException {@code 409 SLOT_UNAVAILABLE} if nobody is free
	 */
	FreeStaff pickStaff(BranchInfo branch, BookingRequirements requirements, Instant start, UUID staffId,
			UUID ignoredAppointmentId, Booker booker, Attempt attempt) {
		List<FreeStaff> free = slots.freeStaff(branch, requirements, start, staffId, ignoredAppointmentId, booker)
			.stream()
			.filter(candidate -> !attempt.excludes(candidate.staffId()))
			.toList();
		if (free.isEmpty()) {
			throw BookingErrors.slotUnavailable();
		}
		FreeStaff chosen = staffId != null ? free.getFirst() : leastBooked(branch, free, start);
		attempt.picked(chosen.staffId(), staffId == null);
		return chosen;
	}

	/**
	 * Decisions 23 and 24 for an account-linked customer: no overlapping blocking appointment
	 * (without buffer, platform-wide) and, for new bookings, fewer than five active ones.
	 * @param ignoredAppointmentId the appointment being rescheduled, or {@code null} for a new booking
	 */
	void checkCustomer(UUID customerUserId, Instant start, Instant end, UUID ignoredAppointmentId) {
		if (appointments.customerHasOverlap(customerUserId, AppointmentStatus.BLOCKING, start, end,
				ignoredAppointmentId != null ? ignoredAppointmentId : NO_APPOINTMENT)) {
			throw BookingErrors.customerOverlap();
		}
		if (ignoredAppointmentId == null && appointments.countByCustomerUserIdAndStatusInAndEndAtAfter(customerUserId,
				AppointmentStatus.BLOCKING, clock.instant()) >= CUSTOMER_LIMIT) {
			throw BookingErrors.customerLimit(CUSTOMER_LIMIT);
		}
	}

	/** The services as booked with the staff member: branch prices, the staff member's durations. */
	static List<AppointmentItem> items(BookingRequirements requirements, UUID staffId) {
		return requirements.services()
			.stream()
			.map(service -> new AppointmentItem(service.serviceId(), service.name(),
					requirements.serviceDurationMinutes(staffId, service.serviceId()), service.price(),
					service.currency(), service.priceFrom()))
			.toList();
	}

	/** For a reschedule: keeps the booked prices, takes the new staff member's durations. */
	static List<AppointmentItem> rescheduledItems(Appointment appointment, BookingRequirements requirements,
			UUID staffId) {
		return appointment.getItems()
			.stream()
			.map(item -> item.withDuration(requirements.serviceDurationMinutes(staffId, item.serviceId())))
			.toList();
	}

	static Instant end(Instant start, List<AppointmentItem> items) {
		return start.plus(Duration.ofMinutes(items.stream().mapToInt(AppointmentItem::durationMinutes).sum()));
	}

	/** Decision 31: the earlier of now + the branch timeout and the start. */
	Instant confirmationDeadline(BranchInfo branch, Instant start) {
		Instant timeout = clock.instant().plus(Duration.ofMinutes(branch.pendingConfirmationTimeoutMinutes()));
		return timeout.isBefore(start) ? timeout : start;
	}

	private FreeStaff leastBooked(BranchInfo branch, List<FreeStaff> free, Instant start) {
		ZoneId zone = branch.timeZone();
		LocalDate date = LocalDate.ofInstant(start, zone);
		Map<UUID, Long> bookedMinutes = new HashMap<>();
		appointments
			.findBySalonIdAndBranchIdAndStaffIdInAndStatusInAndStartAtGreaterThanEqualAndStartAtLessThan(
					branch.salonId(), branch.id(), free.stream().map(FreeStaff::staffId).toList(),
					AppointmentStatus.BLOCKING, date.atStartOfDay(zone).toInstant(),
					date.plusDays(1).atStartOfDay(zone).toInstant())
			.forEach(appointment -> bookedMinutes.merge(appointment.getStaffId(),
					Duration.between(appointment.getStartAt(), appointment.getEndAt()).toMinutes(), Long::sum));
		return free.stream()
			.min(Comparator.<FreeStaff>comparingLong(candidate -> bookedMinutes.getOrDefault(candidate.staffId(), 0L))
				.thenComparing(FreeStaff::staffId))
			.orElseThrow();
	}

}
