package com.lynxis.orca.edge.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.lynxis.orca.edge.domain.EdgeTables.CommandLogEntry;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/** The command log (§C3), read and written through the scope seam. */
@RequiredArgsConstructor
public class CommandLogRepository {

	private static final String TABLE = "command_log";
	private static final String SCOPE_COLUMN = "site_external_id";

	private final ScopeSeam seam;

	/**
	 * Records an outcome.
	 *
	 * <p>Written <strong>after</strong> the physical act and its answer, in one
	 * insert. There is no "record that we are about to try" row, deliberately: a row
	 * that said {@code IN_PROGRESS} would be a recorded outcome that is not one, and
	 * the thing that keeps a second delivery from acting is the idempotency claim,
	 * which already exists and already says so.
	 */
	public int record(CommandLogEntry entry) {
		return seam.insert(ScopedInsert.into(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.value("command_id", entry.commandId())
				.value(SCOPE_COLUMN, entry.siteExternalId())
				.value("lane_external_id", entry.laneExternalId())
				.value("device_external_id", entry.deviceExternalId())
				.value("action", entry.action())
				.value("params", entry.params())
				.value("deadline_ms", entry.deadlineMillis())
				.value("status", entry.status())
				.value("device_response", entry.deviceResponse())
				.value("detail", truncate(entry.detail()))
				.value("acked_at", entry.ackedAt() == null ? null : Timestamp.from(entry.ackedAt())));
	}

	public Optional<CommandLogEntry> byCommandId(String commandId) {
		return seam.select(ScopedSelect.from(TABLE)
								.columns("command_log_id", "command_id", "site_external_id",
										"lane_external_id", "device_external_id", "action", "params",
										"deadline_ms", "status", "device_response", "detail",
										"received_at", "acked_at")
								.scopedBy(SCOPE_COLUMN)
								.where("command_id = ?", commandId),
						(rs, row) -> new CommandLogEntry(
								rs.getLong("command_log_id"),
								rs.getString("command_id"),
								rs.getString("site_external_id"),
								rs.getString("lane_external_id"),
								rs.getString("device_external_id"),
								rs.getString("action"),
								rs.getString("params"),
								rs.getLong("deadline_ms"),
								rs.getString("status"),
								rs.getString("device_response"),
								rs.getString("detail"),
								instant(rs.getTimestamp("received_at")),
								instant(rs.getTimestamp("acked_at"))))
				.stream().findFirst();
	}

	/** The lane's device host, from core's published view (ADR-009, mechanism 2 of §B4). */
	public Optional<String> deviceHostUrlOf(String laneExternalId) {
		return seam.select(ScopedSelect.from("core.topology_lane")
								.columns("device_host_url")
								.scopedBy(SCOPE_COLUMN)
								.where("lane_external_id = ?", laneExternalId),
						(rs, row) -> rs.getString("device_host_url"))
				// Filtered before findFirst: a lane that exists with no device host
				// configured is a NULL in the list, and findFirst throws on one. The
				// caller needs "no host" and "no lane" to be the same empty.
				.stream().filter(java.util.Objects::nonNull).findFirst();
	}

	private static String truncate(String detail) {
		if (detail == null) {
			return null;
		}
		return detail.length() <= 1000 ? detail : detail.substring(0, 1000);
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
