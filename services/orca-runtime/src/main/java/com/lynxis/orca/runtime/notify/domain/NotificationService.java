package com.lynxis.orca.runtime.notify.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.runtime.notify.domain.NotificationTables.Notification;
import com.lynxis.orca.runtime.notify.domain.NotificationTables.WebSocketTicket;
import com.lynxis.orca.runtime.notify.persistence.NotificationRepository;
import com.lynxis.orca.runtime.notify.persistence.NotificationTicketRepository;

import lombok.RequiredArgsConstructor;

/** Durable in-app notifications and the ticket used to open the live channel. */
@RequiredArgsConstructor
public class NotificationService {

	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_LIMIT = 500;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final NotificationRepository notifications;
	private final NotificationTicketRepository tickets;
	private final TransactionTemplate transactions;
	private final String siteExternalId;
	private final Duration ticketTtl;

	public Notification publish(PublishNotification command) {
		String externalId = "notif-" + UUID.randomUUID();
		return notifications.insert(externalId, siteExternalId, command.recipientUserExternalId(),
				command.type(), command.title(), command.message(), command.payloadJson());
	}

	public List<Notification> listFor(String recipientUserExternalId, String type,
			boolean unreadOnly, Integer limit) {
		return notifications.listFor(recipientUserExternalId, type, unreadOnly, boundedLimit(limit));
	}

	public Notification markRead(String externalId, String recipientUserExternalId) {
		return transactions.execute(status -> {
			notifications.markRead(externalId, recipientUserExternalId, Instant.now());
			return notifications.byExternalIdFor(externalId, recipientUserExternalId)
					.orElseThrow(() -> new NotificationNotFoundException(externalId));
		});
	}

	public IssuedTicket issueTicket(String recipientUserExternalId) {
		String token = newToken();
		WebSocketTicket stored = tickets.insert(siteExternalId, recipientUserExternalId, hash(token),
				Instant.now().plus(ticketTtl));
		return new IssuedTicket(token, stored.expiresAt());
	}

	public Optional<TicketClaim> consumeTicket(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		return transactions.execute(status -> tickets.consume(hash(token), Instant.now())
				.map(ticket -> new TicketClaim(ticket.recipientUserExternalId())));
	}

	public static int boundedLimit(Integer limit) {
		return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is not available", impossible);
		}
	}

	public record PublishNotification(
			String recipientUserExternalId,
			String type,
			String title,
			String message,
			String payloadJson) {
	}

	public record IssuedTicket(String token, Instant expiresAt) {
	}

	public record TicketClaim(String recipientUserExternalId) {
	}

	public static class NotificationNotFoundException extends RuntimeException {

		public NotificationNotFoundException(String externalId) {
			super("No notification '" + externalId
					+ "' exists for the calling operator under this installation's scope.");
		}
	}
}
