package com.salon.notification.domain;

import com.salon.shared.ApiException;
import org.springframework.http.HttpStatus;

public final class NotificationErrors {

	private NotificationErrors() {
	}

	/** Also for other users' notifications: their ids cannot be probed. */
	public static ApiException notificationNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "The notification does not exist.");
	}

}
