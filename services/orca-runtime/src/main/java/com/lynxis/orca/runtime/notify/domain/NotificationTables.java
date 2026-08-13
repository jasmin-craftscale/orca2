package com.lynxis.orca.runtime.notify.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/** The tables {@code V140__notifications.sql} creates. */
public final class NotificationTables {

	private NotificationTables() {
	}

	/**
	 * One durable in-app notification for one operator.
	 *
	 * <p>Retention class name is PROVISIONAL. The table grows with operator-visible
	 * events and is bounded by purge policy, not by soft-delete flags copied from
	 * the old schema.
	 */
	@PersistentTable(name = "notification", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("notification") // PROVISIONAL
	public record Notification(
			long notificationId,
			String externalId,
			String siteExternalId,
			String recipientUserExternalId,
			String type,
			String title,
			String message,
			String payloadJson,
			Instant readAt,
			Instant createdAt) {
	}

	/**
	 * One short-lived single-use WebSocket ticket.
	 *
	 * <p>Retention class name is PROVISIONAL. These rows are not the live
	 * subscription map Q1 leaves open; they are the auditably consumed tokens that
	 * stop identity from being accepted from a WebSocket query parameter.
	 */
	@PersistentTable(name = "notification_ws_ticket", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("notification_ws_ticket") // PROVISIONAL
	public record WebSocketTicket(
			long notificationWsTicketId,
			String siteExternalId,
			String recipientUserExternalId,
			String ticketHash,
			Instant issuedAt,
			Instant expiresAt,
			Instant consumedAt) {
	}
}
