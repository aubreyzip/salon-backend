package com.salon.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.salon.booking.application.BookingCommands.BookAtSalon;
import com.salon.booking.application.BookingCommands.Reschedule;
import com.salon.booking.application.BookingExecutor.Attempt;
import com.salon.booking.application.BookingViews.HistoryView;
import com.salon.booking.application.BookingViews.SalonAppointmentDetailView;
import com.salon.booking.application.BookingViews.SalonAppointmentView;
import com.salon.booking.application.Idempotency.Request;
import com.salon.booking.domain.Appointment;
import com.salon.booking.domain.AppointmentHistoryRepository;
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
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import com.salon.salons.SalonMembership;
import com.salon.salons.SalonPermission;
import com.salon.salons.TenantAccess;
import com.salon.scheduling.BookableSlots.Booker;
import com.salon.scheduling.BookableSlots.FreeStaff;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A salon's appointment book (ADR-0013). Reads follow ADR-0010: {@code APPOINTMENT_VIEW_ANY} in the
 * member's branches, or {@code APPOINTMENT_VIEW_OWN} for their own appointments. Changes need
 * {@code APPOINTMENT_MANAGE_ANY} in the appointment's branch, or {@code APPOINTMENT_MANAGE_OWN} for
 * one's own. Salon bookings (decision 37) take every offered service, any whole minute, up to 15
 * minutes in the past and 365 days ahead, also in inactive branches, and are {@code CONFIRMED}.
 */
@Service
public class SalonAppointmentService {

	private final BookingExecutor executor;

	private final BookingRules rules;

	private final CustomerLocks customerLocks;

	private final AppointmentRepository appointments;

	private final AppointmentHistoryRepository history;

	private final SalonClientRepository clients;

	private final AppointmentRecorder recorder;

	private final AppointmentViews views;

	private final TenantAccess access;

	private final SalonDirectory directory;

	private final EntityManager entityManager;

	private final Clock clock;

	SalonAppointmentService(BookingExecutor executor, BookingRules rules, CustomerLocks customerLocks,
			AppointmentRepository appointments, AppointmentHistoryRepository history, SalonClientRepository clients,
			AppointmentRecorder recorder, AppointmentViews views, TenantAccess access, SalonDirectory directory,
			EntityManager entityManager, Clock clock) {
		this.executor = executor;
		this.rules = rules;
		this.customerLocks = customerLocks;
		this.appointments = appointments;
		this.history = history;
		this.clients = clients;
		this.recorder = recorder;
		this.views = views;
		this.access = access;
		this.directory = directory;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/**
	 * List filters; {@code null} means "any".
	 *
	 * @param from appointments ending after this moment
	 * @param to appointments starting before this moment
	 * @param needsAttention only appointments flagged by an override (decision 38)
	 * @param awaitingOutcome only confirmed appointments that have started (decision 33)
	 */
	public record Filter(UUID branchId, UUID staffId, UUID clientId, AppointmentStatus status, Instant from,
			Instant to, Boolean needsAttention, Boolean awaitingOutcome) {
	}

	@Transactional(readOnly = true)
	public Page<SalonAppointmentView> list(UUID salonId, UUID userId, Filter filter, Pageable pageable) {
		SalonMembership member = access.requireAny(salonId, userId, SalonPermission.APPOINTMENT_VIEW_ANY,
				SalonPermission.APPOINTMENT_VIEW_OWN);
		Instant now = clock.instant();
		Specification<Appointment> spec = (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			where.add(cb.equal(root.get("salonId"), salonId));
			List<Predicate> visible = new ArrayList<>();
			if (member.has(SalonPermission.APPOINTMENT_VIEW_ANY)) {
				Optional<Set<UUID>> branches = member.branchRestriction();
				if (branches.isEmpty()) {
					visible.add(cb.conjunction());
				}
				else if (!branches.get().isEmpty()) {
					visible.add(root.get("branchId").in(branches.get()));
				}
			}
			if (member.has(SalonPermission.APPOINTMENT_VIEW_OWN)) {
				visible.add(cb.equal(root.get("staffId"), member.staffId()));
			}
			where.add(visible.isEmpty() ? cb.disjunction() : cb.or(visible.toArray(Predicate[]::new)));
			if (filter.branchId() != null) {
				where.add(cb.equal(root.get("branchId"), filter.branchId()));
			}
			if (filter.staffId() != null) {
				where.add(cb.equal(root.get("staffId"), filter.staffId()));
			}
			if (filter.clientId() != null) {
				where.add(cb.equal(root.get("clientId"), filter.clientId()));
			}
			if (filter.status() != null) {
				where.add(cb.equal(root.get("status"), filter.status()));
			}
			if (filter.from() != null) {
				where.add(cb.greaterThan(root.get("endAt"), filter.from()));
			}
			if (filter.to() != null) {
				where.add(cb.lessThan(root.get("startAt"), filter.to()));
			}
			if (Boolean.TRUE.equals(filter.needsAttention())) {
				where.add(cb.isNotNull(root.get("attentionAt")));
			}
			if (Boolean.TRUE.equals(filter.awaitingOutcome())) {
				where.add(cb.equal(root.get("status"), AppointmentStatus.CONFIRMED));
				where.add(cb.lessThanOrEqualTo(root.get("startAt"), now));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
		Page<Appointment> page = appointments.findAll(spec, pageable);
		return new PageImpl<>(views.forSalon(page.getContent()), page.getPageable(), page.getTotalElements());
	}

	/** With its history. Appointments the caller may not view are not found. */
	@Transactional(readOnly = true)
	public SalonAppointmentDetailView get(UUID salonId, UUID appointmentId, UUID userId) {
		SalonMembership member = access.requireMember(salonId, userId);
		Appointment appointment = appointments.findByIdAndSalonId(appointmentId, salonId)
			.filter(found -> mayView(member, found))
			.orElseThrow(BookingErrors::appointmentNotFound);
		return new SalonAppointmentDetailView(view(appointment),
				history.findByAppointmentIdOrderByOccurredAtAsc(appointmentId).stream().map(HistoryView::of).toList());
	}

	public SalonAppointmentView book(UUID salonId, UUID userId, String idempotencyKey, BookAtSalon command) {
		UUID id = executor.execute(Request.of(userId, idempotencyKey, "salon.book:" + salonId, command), 201,
				attempt -> bookOnce(salonId, userId, command, attempt));
		return executor.read(() -> view(appointments.findByIdAndSalonId(id, salonId).orElseThrow()));
	}

	/** Any future pending or confirmed appointment, at any time; the status is kept (decision 30). */
	public SalonAppointmentView reschedule(UUID salonId, UUID appointmentId, UUID userId, String idempotencyKey,
			Reschedule command) {
		UUID id = executor.execute(
				Request.of(userId, idempotencyKey, "salon.reschedule:" + appointmentId, command), 200,
				attempt -> rescheduleOnce(salonId, appointmentId, userId, command, attempt));
		return executor.read(() -> view(appointments.findByIdAndSalonId(id, salonId).orElseThrow()));
	}

	@Transactional
	public SalonAppointmentView confirm(UUID salonId, UUID appointmentId, UUID userId) {
		return change(salonId, appointmentId, userId, HistoryAction.CONFIRMED, null,
				appointment -> appointment.confirm(clock.instant()));
	}

	@Transactional
	public SalonAppointmentView reject(UUID salonId, UUID appointmentId, UUID userId, String reason) {
		return change(salonId, appointmentId, userId, HistoryAction.REJECTED, reason,
				appointment -> appointment.reject(reason, clock.instant()));
	}

	/** At any time; the reason is required and shown to the customer (decision 34). */
	@Transactional
	public SalonAppointmentView cancel(UUID salonId, UUID appointmentId, UUID userId, String reason) {
		return change(salonId, appointmentId, userId, HistoryAction.CANCELLED, reason,
				appointment -> appointment.cancel(CancelledBy.SALON, reason, clock.instant()));
	}

	@Transactional
	public SalonAppointmentView complete(UUID salonId, UUID appointmentId, UUID userId) {
		return change(salonId, appointmentId, userId, HistoryAction.COMPLETED, null,
				appointment -> appointment.complete(clock.instant()));
	}

	@Transactional
	public SalonAppointmentView markNoShow(UUID salonId, UUID appointmentId, UUID userId) {
		return change(salonId, appointmentId, userId, HistoryAction.NO_SHOW, null,
				appointment -> appointment.markNoShow(clock.instant()));
	}

	/** Clears the attention flag of an override (decision 38) without other changes. */
	@Transactional
	public SalonAppointmentView resolveAttention(UUID salonId, UUID appointmentId, UUID userId) {
		return change(salonId, appointmentId, userId, HistoryAction.ATTENTION_RESOLVED, null,
				appointment -> appointment.resolveAttention(clock.instant()));
	}

	/** The internal note is never shown to the customer; not part of the history. */
	@Transactional
	public SalonAppointmentView changeInternalNote(UUID salonId, UUID appointmentId, UUID userId, String note) {
		Appointment appointment = manage(salonId, appointmentId, userId);
		appointment.changeInternalNote(note, clock.instant());
		return view(appointment);
	}

	private SalonAppointmentView change(UUID salonId, UUID appointmentId, UUID userId, HistoryAction action,
			String reason, Consumer<Appointment> transition) {
		Appointment appointment = manage(salonId, appointmentId, userId);
		AppointmentStatus from = appointment.getStatus();
		transition.accept(appointment);
		recorder.record(appointment, action, from, userId, reason);
		return view(appointment);
	}

	/** Member check, then the appointment, then the manage permission for it (ADR-0010). */
	private Appointment manage(UUID salonId, UUID appointmentId, UUID userId) {
		SalonMembership member = access.requireMember(salonId, userId);
		Appointment appointment = appointments.findByIdAndSalonId(appointmentId, salonId)
			.filter(found -> mayView(member, found))
			.orElseThrow(BookingErrors::appointmentNotFound);
		access.requireForStaffResource(salonId, appointment.getBranchId(), appointment.getStaffId(), userId,
				SalonPermission.APPOINTMENT_MANAGE_ANY, SalonPermission.APPOINTMENT_MANAGE_OWN);
		directory.ensureModifiable(salonId);
		return appointment;
	}

	/**
	 * Access before the locks here: the client must be resolved (404 for other salons) before the
	 * customer lock, and the lock order is customer, then salon. The share lock only orders bookings
	 * against schedule changes.
	 */
	private UUID bookOnce(UUID salonId, UUID userId, BookAtSalon command, Attempt attempt) {
		checkBook(salonId, command.branchId(), command.staffId(), userId);
		BranchInfo branch = directory.findBranch(salonId, command.branchId()).orElseThrow(BookingErrors::branchNotFound);
		SalonClient client = clients.findByIdAndSalonId(command.clientId(), salonId)
			.orElseThrow(BookingErrors::clientNotFound);
		if (client.isLinked()) {
			customerLocks.lock(client.getUserId());
		}
		directory.lockSalonShared(salonId);
		directory.ensureModifiable(salonId);

		BookingRequirements requirements = rules.requirements(branch, command.serviceIds(), false);
		FreeStaff staff = rules.pickStaff(branch, requirements, command.start(), command.staffId(), null, Booker.SALON,
				attempt);
		List<AppointmentItem> items = BookingRules.items(requirements, staff.staffId());
		if (client.isLinked()) {
			rules.checkCustomer(client.getUserId(), command.start(), BookingRules.end(command.start(), items), null);
		}
		Appointment appointment = new Appointment(salonId, branch.id(), staff.staffId(), client.getId(),
				client.getUserId(), AppointmentSource.SALON, command.start(), items, requirements.bufferMinutes(), null,
				null, command.internalNote(), userId, clock.instant());
		appointments.saveAndFlush(appointment);
		recorder.record(appointment, HistoryAction.BOOKED, null, userId, null);
		return appointment.getId();
	}

	private UUID rescheduleOnce(UUID salonId, UUID appointmentId, UUID userId, Reschedule command, Attempt attempt) {
		Appointment appointment = manage(salonId, appointmentId, userId);
		UUID staffId = command.staffId() != null ? command.staffId() : appointment.getStaffId();
		if (!staffId.equals(appointment.getStaffId())) {
			checkBook(salonId, appointment.getBranchId(), staffId, userId);
		}
		if (appointment.getCustomerUserId() != null) {
			customerLocks.lock(appointment.getCustomerUserId());
		}
		directory.lockSalonShared(salonId);
		entityManager.refresh(appointment);
		BranchInfo branch = directory.findBranch(salonId, appointment.getBranchId()).orElseThrow();

		BookingRequirements requirements = rules.requirements(branch, List.copyOf(appointment.serviceIds()), false);
		FreeStaff staff = rules.pickStaff(branch, requirements, command.start(), staffId, appointment.getId(),
				Booker.SALON, attempt);
		List<AppointmentItem> items = BookingRules.rescheduledItems(appointment, requirements, staff.staffId());
		if (appointment.getCustomerUserId() != null) {
			rules.checkCustomer(appointment.getCustomerUserId(), command.start(),
					BookingRules.end(command.start(), items), appointment.getId());
		}
		AppointmentStatus from = appointment.getStatus();
		appointment.reschedule(staff.staffId(), command.start(), items, requirements.bufferMinutes(), null,
				clock.instant());
		appointments.saveAndFlush(appointment);
		recorder.record(appointment, HistoryAction.RESCHEDULED, from, userId, null);
		return appointment.getId();
	}

	/** Booking for a staff member: "any" or own; without a staff member: "any" in the branch. */
	private void checkBook(UUID salonId, UUID branchId, UUID staffId, UUID userId) {
		if (staffId == null) {
			access.requireInBranch(salonId, branchId, userId, SalonPermission.APPOINTMENT_MANAGE_ANY);
		}
		else {
			access.requireForStaffResource(salonId, branchId, staffId, userId, SalonPermission.APPOINTMENT_MANAGE_ANY,
					SalonPermission.APPOINTMENT_MANAGE_OWN);
		}
	}

	/** ADR-0010 appointment reads: any in a covered branch, or one's own. */
	static boolean mayView(SalonMembership member, Appointment appointment) {
		return (member.has(SalonPermission.APPOINTMENT_VIEW_ANY) && member.coversBranch(appointment.getBranchId()))
				|| (member.has(SalonPermission.APPOINTMENT_VIEW_OWN)
						&& member.isOwnStaffResource(appointment.getStaffId()));
	}

	private SalonAppointmentView view(Appointment appointment) {
		return views.forSalon(List.of(appointment)).getFirst();
	}

}
