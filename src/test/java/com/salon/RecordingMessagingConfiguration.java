package com.salon;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.salon.messaging.EmailGateway;
import com.salon.messaging.EmailMessage;
import com.salon.messaging.SmsGateway;
import com.salon.messaging.SmsMessage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the email and SMS gateways with in-memory recorders so tests can read the codes sent.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingMessagingConfiguration {

	private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

	@Bean
	@Primary
	RecordingEmailGateway recordingEmailGateway() {
		return new RecordingEmailGateway();
	}

	@Bean
	@Primary
	RecordingSmsGateway recordingSmsGateway() {
		return new RecordingSmsGateway();
	}

	private static Optional<String> extractCode(String text) {
		Matcher matcher = CODE.matcher(text);
		return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	public static class RecordingEmailGateway implements EmailGateway {

		private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

		@Override
		public void send(EmailMessage message) {
			sent.add(message);
		}

		public List<EmailMessage> sentTo(String to) {
			return sent.stream().filter(message -> message.to().equals(to)).toList();
		}

		public String lastCodeSentTo(String to) {
			List<EmailMessage> messages = sentTo(to);
			if (messages.isEmpty()) {
				throw new AssertionError("No email sent to " + to);
			}
			String body = messages.getLast().body();
			return extractCode(body).orElseThrow(() -> new AssertionError("No code in email: " + body));
		}

	}

	public static class RecordingSmsGateway implements SmsGateway {

		private final List<SmsMessage> sent = new CopyOnWriteArrayList<>();

		@Override
		public void send(SmsMessage message) {
			sent.add(message);
		}

		public List<SmsMessage> sentTo(String to) {
			return sent.stream().filter(message -> message.to().equals(to)).toList();
		}

		public String lastCodeSentTo(String to) {
			List<SmsMessage> messages = sentTo(to);
			if (messages.isEmpty()) {
				throw new AssertionError("No SMS sent to " + to);
			}
			String text = messages.getLast().text();
			return extractCode(text).orElseThrow(() -> new AssertionError("No code in SMS: " + text));
		}

	}

}
