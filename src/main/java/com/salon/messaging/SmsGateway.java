package com.salon.messaging;

/**
 * Sends transactional SMS through the configured provider ({@code salon.messaging.sms.provider}).
 */
public interface SmsGateway {

	void send(SmsMessage message);

}
