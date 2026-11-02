package com.salon.messaging;

/**
 * Sends transactional email through the configured provider ({@code salon.messaging.email.provider}).
 */
public interface EmailGateway {

	void send(EmailMessage message);

}
