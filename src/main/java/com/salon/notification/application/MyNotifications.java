package com.salon.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salon.notification.domain.Notification;
import com.salon.notification.domain.NotificationErrors;
import com.salon.notification.domain.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's in-app notifications (ADR-0014 decision 45): customer and staff
 * notifications alike, newest first. Only the read state can change, and only by the user.
 */
@Service
@Transactional(readOnly = true)
public class MyNotifications {

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final NotificationRepository notifications;

	private final NotificationTexts texts;

	private final Clock clock;

	MyNotifications(NotificationRepository notifications, NotificationTexts texts, Clock clock) {
		this.notifications = notifications;
		this.texts = texts;
		this.clock = clock;
	}

	/**
	 * @param id usable with {@link MyNotifications#markRead}
	 * @param kind what it announces; clients must tolerate kinds added later
	 * @param salonId with {@code branchId} and {@code appointmentId}: what it is about, for links
	 * (customers open their appointment, members the salon's)
	 * @param startAt the appointment start it announces, as it was at the change
	 * @param readAt {@code null} while unread
	 */
	public record NotificationView(UUID id, String kind, String title, String body, UUID salonId, UUID branchId,
			UUID appointmentId, Instant startAt, Instant createdAt, Instant readAt) {
	}

	public Page<NotificationView> list(UUID userId, Pageable pageable) {
		Page<Notification> page = notifications.findByUserId(userId,
				PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST));
		Map<PlaceKey, NotificationTexts.Place> places = new HashMap<>();
		List<NotificationView> views = page.getContent()
			.stream()
			.map(notification -> view(notification, places.computeIfAbsent(
					new PlaceKey(notification.getSalonId(), notification.getBranchId()),
					key -> texts.place(key.salonId(), key.branchId()))))
			.toList();
		return new PageImpl<>(views, page.getPageable(), page.getTotalElements());
	}

	public long unreadCount(UUID userId) {
		return notifications.countByUserIdAndReadAtIsNull(userId);
	}

	/**
	 * Idempotent: reading a read notification keeps the first time.
	 * @throws com.salon.shared.ApiException {@code 404 NOTIFICATION_NOT_FOUND}, also for other users'
	 */
	@Transactional
	public NotificationView markRead(UUID userId, UUID notificationId) {
		Notification notification = notifications.findByIdAndUserId(notificationId, userId)
			.orElseThrow(NotificationErrors::notificationNotFound);
		notification.markRead(clock.instant());
		return view(notification, texts.place(notification.getSalonId(), notification.getBranchId()));
	}

	private record PlaceKey(UUID salonId, UUID branchId) {
	}

	private NotificationView view(Notification notification, NotificationTexts.Place place) {
		NotificationTexts.Text text = texts.render(notification, place, NotificationTexts.TURKISH);
		return new NotificationView(notification.getId(), notification.getKind().name(), text.title(), text.body(),
				notification.getSalonId(), notification.getBranchId(), notification.getAppointmentId(),
				notification.getStartAt(), notification.getCreatedAt(), notification.getReadAt());
	}

}
