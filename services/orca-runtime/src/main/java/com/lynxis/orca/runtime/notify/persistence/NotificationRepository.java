package com.lynxis.orca.runtime.notify.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;
import com.lynxis.orca.runtime.notify.domain.NotificationTables.Notification;
import com.lynxis.orca.runtime.persistence.Utc;

import lombok.RequiredArgsConstructor;

/** Persistence for operator notifications, through the scope seam only. */
@RequiredArgsConstructor
public class NotificationRepository {

	private static final String SCOPE_COLUMN = "site_external_id";
	private static final String[] COLUMNS = { "notification_id", "external_id", "site_external_id",
			"recipient_user_external_id", "notification_type", "title", "message", "payload_json",
			"read_at", "created_at" };

	private final ScopeSeam seam;

	public Notification insert(String externalId, String siteExternalId, String recipientUserExternalId,
			String type, String title, String message, String payloadJson) {
		seam.insert(ScopedInsert.into("notification")
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", externalId)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("recipient_user_external_id", recipientUserExternalId)
				.value("notification_type", type)
				.value("title", title)
				.value("message", message)
				.value("payload_json", payloadJson));
		return byExternalIdFor(externalId, recipientUserExternalId)
				.orElseThrow(() -> new IllegalStateException("Inserted notification was not visible: "
						+ externalId));
	}

	public List<Notification> listFor(String recipientUserExternalId, String type, boolean unreadOnly,
			int limit) {
		StringBuilder where = new StringBuilder("recipient_user_external_id = ?");
		List<Object> parameters = new ArrayList<>();
		parameters.add(recipientUserExternalId);
		if (type != null && !type.isBlank()) {
			where.append(" AND notification_type = ?");
			parameters.add(type);
		}
		if (unreadOnly) {
			where.append(" AND read_at IS NULL");
		}
		return seam.select(ScopedSelect.from("notification")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where(where.toString(), parameters.toArray())
						.orderByDescending("created_at")
						.limit(limit),
				(rs, row) -> map(rs));
	}

	public List<Notification> createdAfter(long notificationId, int limit) {
		return seam.select(ScopedSelect.from("notification")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("notification_id > ?", notificationId)
						.orderBy("notification_id")
						.limit(limit),
				(rs, row) -> map(rs));
	}

	public long maxNotificationId() {
		return seam.select(ScopedSelect.from("notification")
						.columns("notification_id")
						.scopedBy(SCOPE_COLUMN)
						.orderByDescending("notification_id")
						.limit(1),
				(rs, row) -> rs.getLong("notification_id")).stream()
				.findFirst()
				.orElse(0L);
	}

	public Optional<Notification> byExternalIdFor(String externalId, String recipientUserExternalId) {
		return seam.select(ScopedSelect.from("notification")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("external_id = ? AND recipient_user_external_id = ?",
								externalId, recipientUserExternalId),
				(rs, row) -> map(rs)).stream().findFirst();
	}

	public int markRead(String externalId, String recipientUserExternalId, Instant readAt) {
		return seam.update(ScopedUpdate.table("notification")
				.set("read_at", Utc.timestampOf(readAt))
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ? AND recipient_user_external_id = ? AND read_at IS NULL",
						externalId, recipientUserExternalId));
	}

	private static Notification map(ResultSet rs) throws SQLException {
		return new Notification(
				rs.getLong("notification_id"),
				rs.getString("external_id"),
				rs.getString("site_external_id"),
				rs.getString("recipient_user_external_id"),
				rs.getString("notification_type"),
				rs.getString("title"),
				rs.getString("message"),
				rs.getString("payload_json"),
				Utc.instantAt(rs, "read_at"),
				Utc.instantAt(rs, "created_at"));
	}
}
