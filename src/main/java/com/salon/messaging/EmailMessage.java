package com.salon.messaging;

public record EmailMessage(String to, String subject, String body) {
}
