package com.salon.notification.web;

import java.util.UUID;

import com.salon.notification.application.MyNotifications;
import com.salon.notification.application.MyNotifications.NotificationView;
import com.salon.shared.CurrentUserId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's in-app notifications, newest first (ADR-0014 decision 45). Texts are
 * rendered in Turkish (decision 51). {@code kind} values will be added over time; clients show
 * {@code title} and {@code body} for kinds they do not know.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
class MyNotificationsController {

	private final MyNotifications notifications;

	MyNotificationsController(MyNotifications notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	Page<NotificationView> list(@CurrentUserId UUID userId, @PageableDefault(size = 20) Pageable pageable) {
		return notifications.list(userId, pageable);
	}

	/** For badges: cheaper than loading a page. */
	@GetMapping("/unread-count")
	UnreadCount unreadCount(@CurrentUserId UUID userId) {
		return new UnreadCount(notifications.unreadCount(userId));
	}

	/** Idempotent. Other users' notifications are {@code 404 NOTIFICATION_NOT_FOUND}. */
	@PostMapping("/{notificationId}/read")
	NotificationView markRead(@CurrentUserId UUID userId, @PathVariable UUID notificationId) {
		return notifications.markRead(userId, notificationId);
	}

	record UnreadCount(long unread) {
	}

}
