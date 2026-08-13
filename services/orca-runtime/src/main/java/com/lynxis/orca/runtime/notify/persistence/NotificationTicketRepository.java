package com.lynxis.orca.runtime.notify.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;
import com.lynxis.orca.runtime.notify.domain.NotificationTables.WebSocketTicket;
import com.lynxis.orca.runtime.persistence.Utc;

import lombok.RequiredArgsConstructor;

/** Persistence for short-lived single-use notification WebSocket tickets. */
@RequiredArgsConstructor
public class NotificationTicketRepository {

	private static final String SCOPE_COLUMN = "site_external_id";
	private static final String[] COLUMNS = { "notification_ws_ticket_id", "site_external_id",
			"recipient_user_external_id", "ticket_hash", "issued_at", "expires_at", "consumed_at" };

	private final ScopeSeam seam;

	public WebSocketTicket insert(String siteExternalId, String recipientUserExternalId,
			String ticketHash, Instant expiresAt) {
		seam.insert(ScopedInsert.into("notification_ws_ticket")
				.scopedBy(SCOPE_COLUMN)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("recipient_user_external_id", recipientUserExternalId)
				.value("ticket_hash", ticketHash)
				.value("expires_at", Utc.timestampOf(expiresAt)));
		return byHash(ticketHash)
				.orElseThrow(() -> new IllegalStateException("Inserted WebSocket ticket was not visible."));
	}

	public Optional<WebSocketTicket> consume(String ticketHash, Instant consumedAt) {
		int updated = seam.update(ScopedUpdate.table("notification_ws_ticket")
				.set("consumed_at", Utc.timestampOf(consumedAt))
				.scopedBy(SCOPE_COLUMN)
				.where("ticket_hash = ? AND consumed_at IS NULL AND expires_at > SYSUTCDATETIME()",
						ticketHash));
		if (updated != 1) {
			return Optional.empty();
		}
		return byHash(ticketHash);
	}

	public Optional<WebSocketTicket> byHash(String ticketHash) {
		return seam.select(ScopedSelect.from("notification_ws_ticket")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("ticket_hash = ?", ticketHash),
				(rs, row) -> map(rs)).stream().findFirst();
	}

	private static WebSocketTicket map(ResultSet rs) throws SQLException {
		return new WebSocketTicket(
				rs.getLong("notification_ws_ticket_id"),
				rs.getString("site_external_id"),
				rs.getString("recipient_user_external_id"),
				rs.getString("ticket_hash"),
				Utc.instantAt(rs, "issued_at"),
				Utc.instantAt(rs, "expires_at"),
				Utc.instantAt(rs, "consumed_at"));
	}
}
