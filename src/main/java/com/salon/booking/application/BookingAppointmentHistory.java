package com.salon.booking.application;

import java.util.Optional;
import java.util.UUID;

import com.salon.booking.AppointmentHistory;
import com.salon.booking.domain.AppointmentHistoryEntry;
import com.salon.booking.domain.AppointmentHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class BookingAppointmentHistory implements AppointmentHistory {

	private final AppointmentHistoryRepository history;

	BookingAppointmentHistory(AppointmentHistoryRepository history) {
		this.history = history;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Change> find(UUID historyEntryId) {
		return history.findById(historyEntryId).map(BookingAppointmentHistory::change);
	}

	private static Change change(AppointmentHistoryEntry e) {
		return new Change(e.getId(), e.getAppointmentId(), e.getSalonId(), e.getAction().name(),
				e.getFromStatus() == null ? null : e.getFromStatus().name(), e.getToStatus().name(),
				e.getActorUserId(), e.getReason(), e.getStartAt(), e.getEndAt(), e.getStaffId(),
				e.getPreviousStartAt(), e.getPreviousEndAt(), e.getPreviousStaffId(), e.getOccurredAt());
	}

}
