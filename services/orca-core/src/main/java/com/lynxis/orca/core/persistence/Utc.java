package com.lynxis.orca.core.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * {@code DATETIME2} ⇄ {@link Instant}, in UTC, explicitly.
 *
 * <p>The same helper edge carries, for the same reason — {@code rs.getTimestamp}
 * reads a database-written UTC value as JVM-zone wall-clock and is wrong by the
 * machine's offset the moment the appliance is not at UTC. Edge's buffer-age
 * property test exposed this; see {@code orca-edge}'s copy for the full account. Core's
 * {@code created_at}/{@code retired_at} columns are all {@code SYSUTCDATETIME()}
 * defaults, so every read here goes through this.
 */
final class Utc {

	private Utc() {
	}

	/** Reads a {@code DATETIME2} written in UTC. Null-safe. */
	static Instant instantAt(ResultSet rs, String column) throws SQLException {
		LocalDateTime stored = rs.getObject(column, LocalDateTime.class);
		return stored == null ? null : stored.toInstant(ZoneOffset.UTC);
	}

	/** Binds an {@link Instant} as the UTC wall-clock a {@code DATETIME2} column holds. */
	static Timestamp timestampOf(Instant instant) {
		return instant == null ? null
				: Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
	}

	static Timestamp now() {
		return timestampOf(Instant.now());
	}
}
