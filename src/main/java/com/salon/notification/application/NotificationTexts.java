package com.salon.notification.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.salon.notification.domain.Notification;
import com.salon.salons.BranchInfo;
import com.salon.salons.SalonDirectory;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Renders notifications from their templates ({@code notification/messages_*.properties},
 * ADR-0014 decision 51). Turkish only in v1; other locales fall back to Turkish.
 * <p>
 * Times are shown in the branch's time zone. Salon and branch names are read as they are now
 * (current branch facts may be used, decision 56); everything else comes from the notification.
 */
@Component
public class NotificationTexts {

	public static final Locale TURKISH = Locale.forLanguageTag("tr");

	private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Istanbul");

	private final MessageSource messages;

	private final SalonDirectory salons;

	NotificationTexts(SalonDirectory salons) {
		this.salons = salons;
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("notification/messages");
		source.setDefaultEncoding(StandardCharsets.UTF_8.name());
		source.setFallbackToSystemLocale(false);
		source.setDefaultLocale(TURKISH);
		this.messages = source;
	}

	public record Text(String title, String body) {
	}

	/** Where an appointment notification takes place, as shown in its text. */
	public record Place(String salonName, String branchName, ZoneId timeZone) {
	}

	/** The salon and branch of a notification; empty names for notifications without them. */
	public Place place(UUID salonId, UUID branchId) {
		if (salonId == null) {
			return new Place("", "", DEFAULT_ZONE);
		}
		String salonName = salons.findSalonName(salonId).orElse("");
		return (branchId == null ? Optional.<BranchInfo>empty() : salons.findBranch(salonId, branchId))
			.map((BranchInfo branch) -> new Place(salonName, branch.name(), branch.timeZone()))
			.orElse(new Place(salonName, "", DEFAULT_ZONE));
	}

	public Text render(Notification notification, Locale locale) {
		return render(notification, place(notification.getSalonId(), notification.getBranchId()), locale);
	}

	public Text render(Notification notification, Place place, Locale locale) {
		String key = notification.getKind().name();
		Object[] args = { place.salonName(), place.branchName(), format(notification.getStartAt(), place, locale),
				format(notification.getPreviousStartAt(), place, locale) };
		String title = messages.getMessage(key + ".title", args, locale);
		String body = messages.getMessage(key + ".body", args, locale);
		if (notification.getReason() != null && !notification.getReason().isBlank()) {
			body += " " + messages.getMessage("reason", new Object[] { notification.getReason() }, locale);
		}
		return new Text(title, body);
	}

	private static String format(Instant instant, Place place, Locale locale) {
		if (instant == null) {
			return "";
		}
		return DateTimeFormatter.ofPattern("d MMMM EEEE HH:mm", locale).format(instant.atZone(place.timeZone()));
	}

}
