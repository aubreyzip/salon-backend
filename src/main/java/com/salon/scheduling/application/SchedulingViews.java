package com.salon.scheduling.application;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.salon.scheduling.BlockingAppointment;
import com.salon.scheduling.domain.BranchClosure;
import com.salon.scheduling.domain.SpecialDay;
import com.salon.scheduling.domain.TimeInterval;
import com.salon.scheduling.domain.TimeOff;
import com.salon.scheduling.domain.WeeklyInterval;
import com.salon.scheduling.domain.WorkingPeriod;

/**
 * Response views of the scheduling API. Local times are in the branch's time zone.
 */
public final class SchedulingViews {

	private static final Comparator<WeeklyInterval> WEEKLY_ORDER = Comparator.comparing(WeeklyInterval::dayOfWeek)
		.thenComparing(WeeklyInterval::startTime);

	private SchedulingViews() {
	}

	public record WeeklyIntervalView(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {

		static List<WeeklyIntervalView> of(List<WeeklyInterval> intervals) {
			return intervals.stream()
				.sorted(WEEKLY_ORDER)
				.map(i -> new WeeklyIntervalView(i.dayOfWeek(), i.startTime(), i.endTime()))
				.toList();
		}

	}

	public record TimeIntervalView(LocalTime startTime, LocalTime endTime) {

		static List<TimeIntervalView> of(List<TimeInterval> intervals) {
			return intervals.stream().map(i -> new TimeIntervalView(i.startTime(), i.endTime())).toList();
		}

	}

	/**
	 * @param timeZone the branch time zone the local times are in
	 */
	public record OpeningHoursView(UUID branchId, String timeZone, List<WeeklyIntervalView> intervals) {
	}

	/**
	 * @param closed no intervals: the branch is closed that day
	 */
	public record SpecialDayView(LocalDate date, boolean closed, String note, List<TimeIntervalView> intervals) {

		static SpecialDayView of(SpecialDay day) {
			return new SpecialDayView(day.getOnDate(), day.isClosed(), day.getNote(),
					TimeIntervalView.of(day.getIntervals()));
		}

	}

	public record ClosureView(UUID id, LocalDate startDate, LocalDate endDate, String reason) {

		static ClosureView of(BranchClosure closure) {
			return new ClosureView(closure.getId(), closure.getStartDate(), closure.getEndDate(), closure.getReason());
		}

	}

	/**
	 * @param effectiveTo inclusive; {@code null} = open-ended
	 */
	public record PeriodView(LocalDate effectiveFrom, LocalDate effectiveTo, List<WeeklyIntervalView> intervals) {

		static PeriodView of(WorkingPeriod period) {
			return new PeriodView(period.getEffectiveFrom(), period.getEffectiveTo(),
					WeeklyIntervalView.of(period.getIntervals()));
		}

	}

	public record WorkingHoursView(UUID branchId, List<PeriodView> periods) {
	}

	public record TimeOffView(UUID id, UUID staffId, Instant startAt, Instant endAt, String reason) {

		static TimeOffView of(TimeOff timeOff) {
			return new TimeOffView(timeOff.getId(), timeOff.getStaffId(), timeOff.getStartAt(), timeOff.getEndAt(),
					timeOff.getReason());
		}

	}

	/**
	 * An appointment a schedule change would conflict with (in a
	 * {@code 409 SCHEDULE_CONFLICTS_WITH_APPOINTMENTS} problem).
	 *
	 * @param end when the staff member is free again (services plus cleanup buffer)
	 */
	public record ConflictingAppointmentView(UUID id, UUID branchId, UUID staffId, Instant start, Instant end) {

		static ConflictingAppointmentView of(BlockingAppointment appointment) {
			return new ConflictingAppointmentView(appointment.id(), appointment.branchId(), appointment.staffId(),
					appointment.start(), appointment.blockEnd());
		}

	}

	/**
	 * Bookable start times at a branch for a set of services.
	 *
	 * @param staff the staff members who can perform all services, with their total duration
	 * @param days every day of the requested range; days without slots have an empty list
	 */
	public record AvailabilityView(UUID branchId, String timeZone, List<ServiceSummary> services,
			List<StaffSummary> staff, List<DayView> days) {
	}

	public record ServiceSummary(UUID id, String name, int durationMinutes, BigDecimal price, String currency,
			boolean priceFrom) {
	}

	public record StaffSummary(UUID id, String displayName, int durationMinutes) {
	}

	public record DayView(LocalDate date, List<SlotView> slots) {
	}

	/**
	 * @param start the start as an instant (UTC)
	 * @param localTime the start in the branch time zone
	 * @param staffIds the staff members free for the whole booking at this start
	 */
	public record SlotView(Instant start, LocalTime localTime, List<UUID> staffIds) {
	}

}
