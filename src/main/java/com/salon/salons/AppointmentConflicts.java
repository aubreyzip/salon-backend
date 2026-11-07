package com.salon.salons;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.salon.audit.AuditLog;
import com.salon.audit.AuditRecord;
import com.salon.salons.domain.SalonErrors;
import com.salon.shared.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The validation boundary for changes that affect future appointments (ADR-0012 decisions 21 and
 * 26), shared by {@code salons}, {@code catalog} and {@code scheduling}. A conflicting change is
 * rejected with {@code 409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS}, unless the caller sends an
 * explicit {@link ConflictOverride} and holds {@code APPOINTMENT_CONFLICT_OVERRIDE} (decision 25):
 * then the change goes through and the appointments are flagged for attention (ADR-0013, decision 38).
 * <p>
 * Appointments come from every {@link BlockingAppointments} in the context. Callers run the check
 * after their access checks and under the salon row lock.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AppointmentConflicts {

	public static final String CODE = "SCHEDULE_CONFLICTS_WITH_APPOINTMENTS";

	private final List<BlockingAppointments> sources;

	private final AuditLog auditLog;

	private final Clock clock;

	AppointmentConflicts(List<BlockingAppointments> sources, AuditLog auditLog, Clock clock) {
		this.sources = sources;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * An appointment a change conflicts with, as listed in the 409 problem.
	 *
	 * @param end when the staff member is free again (services plus cleanup buffer)
	 */
	public record ConflictingAppointmentView(UUID id, UUID branchId, UUID staffId, Instant start, Instant end) {

		static ConflictingAppointmentView of(BlockingAppointment appointment) {
			return new ConflictingAppointmentView(appointment.id(), appointment.branchId(), appointment.staffId(),
					appointment.start(), appointment.blockEnd());
		}

	}

	/** Future blocking appointments at the branch. */
	public List<BlockingAppointment> atBranch(UUID salonId, UUID branchId) {
		Instant now = clock.instant();
		return collect(source -> source.atBranch(salonId, branchId, now));
	}

	/** Future blocking appointments of the staff member, at any branch. */
	public List<BlockingAppointment> ofStaff(UUID salonId, UUID staffId) {
		Instant now = clock.instant();
		return collect(source -> source.ofStaff(salonId, staffId, now));
	}

	/** Future blocking appointments that include the service. */
	public List<BlockingAppointment> withService(UUID salonId, UUID serviceId) {
		Instant now = clock.instant();
		return collect(source -> source.withService(salonId, serviceId, now));
	}

	/**
	 * Rejects the change if any appointment conflicts, or applies the override.
	 * @param change what is being changed, for the audit log (no personal data)
	 * @param override the caller's explicit override, or {@code null}
	 * @throws ApiException {@code 409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS} without an override (the
	 * problem lists only the appointments the caller may view); {@code 403 INSUFFICIENT_PERMISSION}
	 * with an override but without {@code APPOINTMENT_CONFLICT_OVERRIDE}
	 */
	public void check(SalonMembership caller, List<BlockingAppointment> appointments,
			Predicate<BlockingAppointment> conflicts, ConflictOverride override, String change) {
		List<BlockingAppointment> conflicting = appointments.stream()
			.filter(conflicts)
			.collect(Collectors.toMap(BlockingAppointment::id, Function.identity(), (a, b) -> a))
			.values()
			.stream()
			.sorted(Comparator.comparing(BlockingAppointment::start))
			.toList();
		if (conflicting.isEmpty()) {
			return;
		}
		if (override == null) {
			List<ConflictingAppointmentView> visible = conflicting.stream()
				.filter(appointment -> visibleTo(caller, appointment))
				.map(ConflictingAppointmentView::of)
				.toList();
			throw new ApiException(HttpStatus.CONFLICT, CODE,
					"The change conflicts with " + conflicting.size()
							+ " upcoming appointment(s). Move or cancel them first, or override.",
					Map.of("conflictCount", conflicting.size(), "appointments", visible));
		}
		if (!caller.has(SalonPermission.APPOINTMENT_CONFLICT_OVERRIDE)) {
			throw SalonErrors.insufficientPermission();
		}
		List<UUID> ids = conflicting.stream().map(BlockingAppointment::id).toList();
		sources.forEach(source -> source.flagForAttention(caller.salonId(), ids, override.reason(), caller.userId()));
		// The reason is not audited: it may contain personal data. It is stored with the appointments.
		auditLog.record(new AuditRecord(caller.userId(), caller.salonId(), "appointments.conflict_overridden",
				"salon", caller.salonId().toString(), change + ": " + ids.size() + " appointment(s) flagged"));
	}

	/** Same rules as appointment reads (ADR-0010): any in a covered branch, or one's own. */
	public static boolean visibleTo(SalonMembership caller, BlockingAppointment appointment) {
		return (caller.has(SalonPermission.APPOINTMENT_VIEW_ANY) && caller.coversBranch(appointment.branchId()))
				|| (caller.has(SalonPermission.APPOINTMENT_VIEW_OWN)
						&& caller.isOwnStaffResource(appointment.staffId()));
	}

	private List<BlockingAppointment> collect(Function<BlockingAppointments, List<BlockingAppointment>> query) {
		return sources.stream().flatMap(source -> query.apply(source).stream()).toList();
	}

}
