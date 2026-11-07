package com.salon.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.salon.shared.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

/**
 * An appointment: one staff member performs the booked services back to back at one branch
 * (ADR-0012 decision 2). The staff member is busy until {@code blockEndAt} (end plus the cleanup
 * buffer). Status changes are methods that enforce the allowed moves (report §7, ADR-0013); every
 * change is also written to the history by the caller. Concurrent changes are resolved by
 * optimistic locking: one wins, the other gets {@code 409 CONCURRENT_MODIFICATION}.
 */
@Entity
@Table(name = "appointment")
public class Appointment extends BaseEntity {

	private UUID salonId;

	private UUID branchId;

	private UUID staffId;

	private UUID clientId;

	private UUID customerUserId;

	@Enumerated(EnumType.STRING)
	private AppointmentStatus status;

	@Enumerated(EnumType.STRING)
	private AppointmentSource source;

	private Instant startAt;

	private Instant endAt;

	private Instant blockEndAt;

	private int bufferMinutes;

	private Instant confirmationDeadline;

	private String customerNote;

	private String internalNote;

	private String statusReason;

	@Enumerated(EnumType.STRING)
	private CancelledBy cancelledBy;

	private String attentionReason;

	private Instant attentionAt;

	private UUID attentionByUserId;

	private UUID createdByUserId;

	private Instant createdAt;

	private Instant updatedAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "appointment_item", joinColumns = @JoinColumn(name = "appointment_id"))
	@OrderColumn(name = "position")
	@BatchSize(size = 100)
	private List<AppointmentItem> items = new ArrayList<>();

	protected Appointment() {
	}

	/**
	 * @param customerUserId the account-linked customer, or {@code null} for walk-in clients
	 * @param confirmationDeadline when a {@code PENDING} booking expires; {@code null} books it
	 * {@code CONFIRMED}
	 */
	public Appointment(UUID salonId, UUID branchId, UUID staffId, UUID clientId, UUID customerUserId,
			AppointmentSource source, Instant startAt, List<AppointmentItem> items, int bufferMinutes,
			Instant confirmationDeadline, String customerNote, String internalNote, UUID createdByUserId,
			Instant now) {
		this.salonId = salonId;
		this.branchId = branchId;
		this.staffId = staffId;
		this.clientId = clientId;
		this.customerUserId = customerUserId;
		this.source = source;
		this.status = confirmationDeadline == null ? AppointmentStatus.CONFIRMED : AppointmentStatus.PENDING;
		this.confirmationDeadline = confirmationDeadline;
		this.customerNote = customerNote;
		this.internalNote = internalNote;
		this.createdByUserId = createdByUserId;
		this.createdAt = now;
		this.updatedAt = now;
		schedule(staffId, startAt, items, bufferMinutes);
	}

	public void confirm(Instant now) {
		require(AppointmentStatus.PENDING, "confirmed");
		if (!now.isBefore(confirmationDeadline)) {
			throw BookingErrors.invalidTransition("The confirmation deadline has passed.");
		}
		status = AppointmentStatus.CONFIRMED;
		confirmationDeadline = null;
		updatedAt = now;
	}

	public void reject(String reason, Instant now) {
		require(AppointmentStatus.PENDING, "rejected");
		status = AppointmentStatus.REJECTED;
		statusReason = reason;
		updatedAt = now;
	}

	/** By the expiry job: the salon did not confirm in time (ADR-0009). */
	public void expire(Instant now) {
		require(AppointmentStatus.PENDING, "expired");
		status = AppointmentStatus.EXPIRED;
		updatedAt = now;
	}

	public void cancel(CancelledBy by, String reason, Instant now) {
		if (!status.isBlocking()) {
			throw BookingErrors.invalidTransition("Only pending or confirmed appointments can be cancelled.");
		}
		status = AppointmentStatus.CANCELLED;
		cancelledBy = by;
		statusReason = reason;
		clearAttention();
		updatedAt = now;
	}

	/** The outcome, once the appointment has started (decision 33). Final. */
	public void complete(Instant now) {
		requireStarted(now, "completed");
		status = AppointmentStatus.COMPLETED;
		updatedAt = now;
	}

	public void markNoShow(Instant now) {
		requireStarted(now, "marked as no-show");
		status = AppointmentStatus.NO_SHOW;
		updatedAt = now;
	}

	/**
	 * Moves the appointment (same row, so the old slot is released and the new one claimed in one
	 * transaction). Clears the attention flag.
	 * @param newConfirmationDeadline puts the appointment back to {@code PENDING} (customer
	 * reschedule with manual confirmation, decision 30), or {@code null} to keep the status
	 */
	public void reschedule(UUID newStaffId, Instant newStart, List<AppointmentItem> newItems, int newBufferMinutes,
			Instant newConfirmationDeadline, Instant now) {
		if (!status.isBlocking()) {
			throw BookingErrors.invalidTransition("Only pending or confirmed appointments can be rescheduled.");
		}
		schedule(newStaffId, newStart, newItems, newBufferMinutes);
		if (newConfirmationDeadline != null) {
			status = AppointmentStatus.PENDING;
			confirmationDeadline = newConfirmationDeadline;
		}
		clearAttention();
		updatedAt = now;
	}

	/** Emergency override (decision 38): keeps the status, flags the appointment. */
	public void flagForAttention(String reason, UUID actorUserId, Instant now) {
		attentionReason = reason;
		attentionAt = now;
		attentionByUserId = actorUserId;
		updatedAt = now;
	}

	public void resolveAttention(Instant now) {
		if (!needsAttention()) {
			throw BookingErrors.invalidTransition("The appointment is not flagged.");
		}
		clearAttention();
		updatedAt = now;
	}

	public void changeInternalNote(String note, Instant now) {
		internalNote = note;
		updatedAt = now;
	}

	/** Whether customers may still cancel or reschedule: before the branch's cutoff (decision 29). */
	public boolean customerMayChange(Instant now, int cancellationCutoffMinutes) {
		return now.plus(Duration.ofMinutes(cancellationCutoffMinutes)).isBefore(startAt);
	}

	public boolean needsAttention() {
		return attentionAt != null;
	}

	public Set<UUID> serviceIds() {
		Set<UUID> ids = new LinkedHashSet<>();
		items.forEach(item -> ids.add(item.serviceId()));
		return ids;
	}

	private void schedule(UUID newStaffId, Instant newStart, List<AppointmentItem> newItems, int newBufferMinutes) {
		if (newItems.isEmpty()) {
			throw new IllegalArgumentException("An appointment needs at least one service.");
		}
		int minutes = newItems.stream().mapToInt(AppointmentItem::durationMinutes).sum();
		staffId = newStaffId;
		startAt = newStart;
		endAt = newStart.plus(Duration.ofMinutes(minutes));
		bufferMinutes = newBufferMinutes;
		blockEndAt = endAt.plus(Duration.ofMinutes(newBufferMinutes));
		items.clear();
		items.addAll(newItems);
	}

	private void clearAttention() {
		attentionReason = null;
		attentionAt = null;
		attentionByUserId = null;
	}

	private void require(AppointmentStatus expected, String action) {
		if (status != expected) {
			throw BookingErrors.invalidTransition(
					"A " + status.name().toLowerCase(Locale.ROOT) + " appointment cannot be " + action + ".");
		}
	}

	private void requireStarted(Instant now, String action) {
		require(AppointmentStatus.CONFIRMED, action);
		if (now.isBefore(startAt)) {
			throw BookingErrors.notStarted();
		}
	}

	public UUID getSalonId() {
		return salonId;
	}

	public UUID getBranchId() {
		return branchId;
	}

	public UUID getStaffId() {
		return staffId;
	}

	public UUID getClientId() {
		return clientId;
	}

	public UUID getCustomerUserId() {
		return customerUserId;
	}

	public AppointmentStatus getStatus() {
		return status;
	}

	public AppointmentSource getSource() {
		return source;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public Instant getBlockEndAt() {
		return blockEndAt;
	}

	public int getBufferMinutes() {
		return bufferMinutes;
	}

	public Instant getConfirmationDeadline() {
		return status == AppointmentStatus.PENDING ? confirmationDeadline : null;
	}

	public String getCustomerNote() {
		return customerNote;
	}

	public String getInternalNote() {
		return internalNote;
	}

	public String getStatusReason() {
		return statusReason;
	}

	public CancelledBy getCancelledBy() {
		return cancelledBy;
	}

	public String getAttentionReason() {
		return attentionReason;
	}

	public Instant getAttentionAt() {
		return attentionAt;
	}

	public UUID getAttentionByUserId() {
		return attentionByUserId;
	}

	public UUID getCreatedByUserId() {
		return createdByUserId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<AppointmentItem> getItems() {
		return List.copyOf(items);
	}

}
