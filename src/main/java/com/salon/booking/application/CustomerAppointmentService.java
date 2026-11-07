package com.salon.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.salon.booking.application.BookingCommands.BookOnline;
import com.salon.booking.application.BookingCommands.Reschedule;
import com.salon.booking.application.BookingExecutor.Attempt;
import com.salon.booking.application.BookingViews.CustomerAppointmentView;
import com.salon.booking.application.Idempotency.Request;
import com.salon.booking.domain.Appointment;
import com.salon.booking.domain.AppointmentItem;
import com.salon.booking.domain.AppointmentRepository;
import com.salon.booking.domain.AppointmentSource;
import com.salon.booking.domain.AppointmentStatus;
import com.salon.booking.domain.BookingErrors;
import com.salon.booking.domain.CancelledBy;
import com.salon.booking.domain.HistoryAction;
import com.salon.booking.domain.SalonClient;
import com.salon.booking.domain.SalonClientRepository;
import com.salon.catalog.BookingRequirements;
import com.salon.identity.UserDirectory;
import com.salon.identity.UserSummary;
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import com.salon.scheduling.BookableSlots.Booker;
import com.salon.scheduling.BookableSlots.FreeStaff;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's own appointments (ADR-0013 decisions 27-31, 40, 41). Online bookings need a public
 * branch (active branch of an active salon), an active account with a verified phone (ADR-0007),
 * and follow the branch's grid, notice and advance limits. Bookings and reschedules lock the
 * customer, then the salon row in share mode; the exclusion constraints are the final arbiter.
 */
@Service
public class CustomerAppointmentService {

	private final BookingExecutor executor;

	private final BookingRules rules;

	private final CustomerLocks customerLocks;

	private final AppointmentRepository appointments;

	private final SalonClientRepository clients;

	private final AppointmentRecorder recorder;

	private final AppointmentViews views;

	private final UserDirectory users;

	private final SalonDirectory directory;

	private final EntityManager entityManager;

	private final Clock clock;

	CustomerAppointmentService(BookingExecutor executor, BookingRules rules, CustomerLocks customerLocks,
			AppointmentRepository appointments, SalonClientRepository clients, AppointmentRecorder recorder,
			AppointmentViews views, UserDirectory users, SalonDirectory directory, EntityManager entityManager,
			Clock clock) {
		this.executor = executor;
		this.rules = rules;
		this.customerLocks = customerLocks;
		this.appointments = appointments;
		this.clients = clients;
		this.recorder = recorder;
		this.views = views;
		this.users = users;
		this.directory = directory;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/** Which of the customer's appointments to list. */
	public enum Scope {

		/** Pending or confirmed and not yet over, soonest first. */
		UPCOMING,

		/** Everything else, most recent first. */
		PAST

	}

	public CustomerAppointmentView book(UUID userId, String idempotencyKey, BookOnline command) {
		UUID id = executor.execute(Request.of(userId, idempotencyKey, "customer.book", command), 201,
				attempt -> bookOnce(userId, command, attempt));
		return executor.read(() -> view(load(userId, id)));
	}

	public CustomerAppointmentView reschedule(UUID userId, UUID appointmentId, String idempotencyKey,
			Reschedule command) {
		UUID id = executor.execute(
				Request.of(userId, idempotencyKey, "customer.reschedule:" + appointmentId, command), 200,
				attempt -> rescheduleOnce(userId, appointmentId, command, attempt));
		return executor.read(() -> view(load(userId, id)));
	}

	@Transactional(readOnly = true)
	public Page<CustomerAppointmentView> list(UUID userId, Scope scope, Pageable pageable) {
		Instant now = clock.instant();
		Page<Appointment> page = scope == Scope.UPCOMING
				? appointments.findByCustomerUserIdAndStatusInAndEndAtAfter(userId, AppointmentStatus.BLOCKING, now,
						PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("startAt").ascending()))
				: appointments.findPastOfCustomer(userId, AppointmentStatus.BLOCKING, now,
						PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("startAt").descending()));
		return new PageImpl<>(views.forCustomer(page.getContent()), page.getPageable(), page.getTotalElements());
	}

	@Transactional(readOnly = true)
	public CustomerAppointmentView get(UUID userId, UUID appointmentId) {
		return view(load(userId, appointmentId));
	}

	/**
	 * Until the branch's cancellation cutoff (decision 29); the reason is optional.
	 * @throws com.salon.shared.ApiException {@code 409 CANCELLATION_WINDOW_CLOSED} after the cutoff
	 */
	@Transactional
	public CustomerAppointmentView cancel(UUID userId, UUID appointmentId, String reason) {
		Appointment appointment = load(userId, appointmentId);
		checkChangeable(appointment);
		AppointmentStatus from = appointment.getStatus();
		appointment.cancel(CancelledBy.CUSTOMER, reason, clock.instant());
		recorder.record(appointment, HistoryAction.CANCELLED, from, userId, reason);
		return view(appointment);
	}

	private UUID bookOnce(UUID userId, BookOnline command, Attempt attempt) {
		UserSummary user = users.find(userId).filter(UserSummary::active).orElseThrow(BookingErrors::accountInactive);
		if (!user.phoneVerified()) {
			throw BookingErrors.phoneNotVerified();
		}
		BranchInfo branch = directory.findPublicBranch(command.branchId()).orElseThrow(BookingErrors::branchNotFound);
		customerLocks.lock(userId);
		directory.lockSalonShared(branch.salonId());

		BookingRequirements requirements = rules.requirements(branch, command.serviceIds(), true);
		FreeStaff staff = rules.pickStaff(branch, requirements, command.start(), command.staffId(), null,
				Booker.CUSTOMER, attempt);
		List<AppointmentItem> items = BookingRules.items(requirements, staff.staffId());
		rules.checkCustomer(userId, command.start(), BookingRules.end(command.start(), items), null);

		Instant now = clock.instant();
		SalonClient client = clients.findBySalonIdAndUserId(branch.salonId(), userId)
			.orElseGet(() -> clients.save(SalonClient.linked(branch.salonId(), userId, now)));
		Appointment appointment = new Appointment(branch.salonId(), branch.id(), staff.staffId(), client.getId(), userId,
				AppointmentSource.ONLINE, command.start(), items, requirements.bufferMinutes(),
				branch.autoConfirm() ? null : rules.confirmationDeadline(branch, command.start()),
				command.customerNote(), null, userId, now);
		appointments.saveAndFlush(appointment);
		recorder.record(appointment, HistoryAction.BOOKED, null, userId, null);
		return appointment.getId();
	}

	private UUID rescheduleOnce(UUID userId, UUID appointmentId, Reschedule command, Attempt attempt) {
		Appointment appointment = load(userId, appointmentId);
		customerLocks.lock(userId);
		directory.lockSalonShared(appointment.getSalonId());
		entityManager.refresh(appointment);
		checkChangeable(appointment);
		BranchInfo branch = directory.findPublicBranch(appointment.getBranchId())
			.orElseThrow(BookingErrors::branchNotBookable);

		BookingRequirements requirements = rules.requirements(branch, List.copyOf(appointment.serviceIds()), true);
		UUID staffId = command.staffId() != null ? command.staffId() : appointment.getStaffId();
		FreeStaff staff = rules.pickStaff(branch, requirements, command.start(), staffId, appointment.getId(),
				Booker.CUSTOMER, attempt);
		List<AppointmentItem> items = BookingRules.rescheduledItems(appointment, requirements, staff.staffId());
		rules.checkCustomer(userId, command.start(), BookingRules.end(command.start(), items), appointment.getId());

		AppointmentStatus from = appointment.getStatus();
		// Decision 30: with manual confirmation, the salon approves the new time.
		Instant deadline = branch.autoConfirm() ? null : rules.confirmationDeadline(branch, command.start());
		appointment.reschedule(staff.staffId(), command.start(), items, requirements.bufferMinutes(), deadline,
				clock.instant());
		appointments.saveAndFlush(appointment);
		recorder.record(appointment, HistoryAction.RESCHEDULED, from, userId, null);
		return appointment.getId();
	}

	private void checkChangeable(Appointment appointment) {
		if (!appointment.getStatus().isBlocking()) {
			throw BookingErrors.invalidTransition("Only pending or confirmed appointments can be changed.");
		}
		int cutoff = directory.findBranch(appointment.getSalonId(), appointment.getBranchId())
			.map(BranchInfo::cancellationCutoffMinutes)
			.orElse(0);
		if (!appointment.customerMayChange(clock.instant(), cutoff)) {
			throw BookingErrors.cancellationWindowClosed();
		}
	}

	private Appointment load(UUID userId, UUID appointmentId) {
		return appointments.findByIdAndCustomerUserId(appointmentId, userId)
			.orElseThrow(BookingErrors::appointmentNotFound);
	}

	private CustomerAppointmentView view(Appointment appointment) {
		return views.forCustomer(List.of(appointment)).getFirst();
	}

}
