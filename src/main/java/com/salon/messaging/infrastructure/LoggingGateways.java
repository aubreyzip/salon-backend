package com.salon.messaging.infrastructure;

import com.salon.messaging.EmailGateway;
import com.salon.messaging.SmsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Placeholder gateways that write messages to the application log instead of delivering them.
 * <p>
 * They exist so that verification flows work before real email and SMS providers are chosen.
 * Messages contain verification codes, so these gateways must never be active in production.
 */
@Configuration(proxyBeanMethods = false)
class LoggingGateways {

	private static final Logger log = LoggerFactory.getLogger(LoggingGateways.class);

	@Bean
	@ConditionalOnProperty(name = "salon.messaging.email.provider", havingValue = "log", matchIfMissing = true)
	EmailGateway loggingEmailGateway() {
		log.warn("Email provider is the logging placeholder: emails are written to the log, not delivered. "
				+ "Never use this in production.");
		return message -> log.info("[EMAIL placeholder] to={} subject=\"{}\"\n{}", message.to(), message.subject(),
				message.body());
	}

	@Bean
	@ConditionalOnProperty(name = "salon.messaging.sms.provider", havingValue = "log", matchIfMissing = true)
	SmsGateway loggingSmsGateway() {
		log.warn("SMS provider is the logging placeholder: SMS are written to the log, not delivered. "
				+ "Never use this in production.");
		return message -> log.info("[SMS placeholder] to={} text=\"{}\"", message.to(), message.text());
	}

}
