package com.salon.messaging;

/**
 * @param to recipient phone number in E.164 format
 */
public record SmsMessage(String to, String text) {
}
