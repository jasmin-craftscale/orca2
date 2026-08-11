package com.lynxis.orca.runtime.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * {@code DATETIME2} ⇄ {@link Instant}, in UTC, explicitly.
 *
 * <p>{@code DATETIME2} stores a wall-clock reading with no zone. Runtime columns
 * are written both by SQL defaults such as {@code SYSUTCDATETIME()} and by Java,
 * so every Java read and write must apply UTC explicitly. A zone-less Java write
 * followed by a zone-less Java read appears to round-trip because the two errors
 * cancel; a database-written value exposes the machine's offset immediately.
 *
 * <p>This mirrors the established helper in orca-edge. It remains service-local:
 * importing edge is forbidden by the module wall, while promoting a second copy
 * to platform would be a broader six-service design decision.
 */
public final class Utc {

	private Utc() {
	}

	/** Reads a {@code DATETIME2} written in UTC. Null-safe. */
	public static Instant instantAt(ResultSet rs, String column) throws SQLException {
		LocalDateTime stored = rs.getObject(column, LocalDateTime.class);
		return stored == null ? null : stored.toInstant(ZoneOffset.UTC);
	}

	/** Binds an {@link Instant} as the UTC wall-clock a {@code DATETIME2} column holds. */
	public static Timestamp timestampOf(Instant instant) {
		return instant == null ? null
				: Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
	}

	public static Timestamp now() {
		return timestampOf(Instant.now());
	}
}
