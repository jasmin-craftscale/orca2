package com.lynxis.orca.edge.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * {@code DATETIME2} ⇄ {@link Instant}, in UTC, explicitly.
 *
 * <h2>The bug this exists to prevent, found by running H3's own test</h2>
 *
 * <p>{@code java.sql.Timestamp} carries no zone. {@code rs.getTimestamp(column)}
 * therefore reads a {@code DATETIME2} as <strong>wall-clock time in the JVM's
 * default zone</strong>, and {@code Timestamp.from(instant)} writes the same way.
 * That round-trips correctly for a value Java both wrote and read — and is wrong by
 * the machine's UTC offset for every value the <em>database</em> wrote.
 *
 * <p>{@code event_buffer.received_at} defaults to {@code SYSUTCDATETIME()}, so it is
 * one of those. Reading it through the zone-less path on a machine at UTC+2 made
 * {@code /internal/buffer/stats} report an event buffered one second ago as
 * <strong>two hours old</strong>. H3's age assertion failed on exactly that, which is
 * why it is written as a property and not as a smoke test: an operator reading "the
 * oldest event here is two hours old" concludes the link is severed and starts
 * looking at a network that is fine.
 *
 * <p>The conversion is stated in both directions rather than inherited from the
 * JVM's zone, so the answer does not depend on where the appliance is installed.
 *
 * <h2>⚠️ The same pattern exists elsewhere and is NOT fixed here</h2>
 *
 * <p>Reported rather than swept: {@code platform/outbox}'s {@code created_at} and
 * {@code platform/lease}'s {@code expires_at} are also database-written and read
 * through the zone-less path. Neither is a live defect today — the lease and the
 * relay do every time <em>comparison</em> inside SQL against {@code SYSUTCDATETIME()}
 * and never against a JVM clock, which {@code JdbcLeaseManager}'s Javadoc calls out
 * deliberately — so the skew is confined to values they merely report. It is still
 * the same trap, one primitive away.
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
