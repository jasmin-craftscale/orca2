package com.lynxis.orca.edge.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * The durable capture buffer, read and written through the scope seam.
 *
 * <p>Everything here goes through {@link ScopeSeam}. That is not ceremony: it is
 * {@code ScopeSeamRule} fails the build if this
 * class for touching a {@code JdbcTemplate}, and until the seam could write there
 * was no legal way for a service to insert a row at all.
 *
 * <p><strong>The scope dimension is the installation's own site</strong>, supplied
 * by configuration and established by whichever background task is running — see
 * {@code IngestScope}. A pump that forgot to establish it reads nothing and writes
 * nothing, loudly, rather than draining another site's buffer.
 */
@RequiredArgsConstructor
public class EventBufferRepository {

	private static final String TABLE = "event_buffer";
	private static final String SCOPE_COLUMN = "site_external_id";

	private final ScopeSeam seam;

	/**
	 * Appends one capture.
	 *
	 * <p>Called <strong>before</strong> the camera is acknowledged. The
	 * acknowledgement is a durability receipt, not a courtesy: a camera that has
	 * been told "received" will not send that capture again, so acknowledging
	 * before the row commits converts a restart into lost traffic.
	 *
	 * @return 1 when the row landed, 0 when this uuid was already buffered
	 */
	public int append(BufferedEvent event) {
		if (exists(event.siteExternalId(), event.eventUuid())) {
			// A camera retrying after a lost acknowledgement. Same uuid, same event:
			// the dedup key is doing exactly what it is for.
			return 0;
		}
		return seam.insert(ScopedInsert.into(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.value("event_uuid", event.eventUuid())
				.value(SCOPE_COLUMN, event.siteExternalId())
				.value("lane_external_id", event.laneExternalId())
				.value("device_external_id", event.deviceExternalId())
				.value("event_type", event.eventType())
				.value("payload", event.payload())
				.value("attributes", event.attributes())
				.value("status", BufferedEvent.PENDING)
				.value("attempts", 0));
	}

	public boolean exists(String siteExternalId, String eventUuid) {
		return seam.count(ScopedSelect.from(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.where("event_uuid = ?", eventUuid)) > 0;
	}

	/**
	 * This lane's undelivered events, oldest first.
	 *
	 * <p><strong>Order is the guarantee</strong>, not a convenience: the buffer promises
	 * zero loss <em>and preserved order</em> when the link returns. Ordering by the
	 * identity column rather than by {@code received_at} is what makes that true
	 * inside a millisecond.
	 *
	 * <p>{@code DISPATCHED} rows are included. A batch that was sent and never
	 * acknowledged has to be sent again — at-least-once, with runtime's idempotency
	 * key doing the deduplicating at the far end.
	 */
	public List<BufferedEvent> undelivered(String siteExternalId, String laneExternalId, int batchSize) {
		return seam.select(ScopedSelect.from(TABLE)
						.columns("sequence_no", "event_uuid", "site_external_id", "lane_external_id",
								"device_external_id", "event_type", "payload", "attributes", "status",
								"attempts", "last_error", "received_at", "dispatched_at", "acked_at")
						.scopedBy(SCOPE_COLUMN)
						.where("lane_external_id = ? AND status IN ('PENDING', 'DISPATCHED')", laneExternalId)
						.orderBy("sequence_no")
						.limit(batchSize),
				(rs, row) -> new BufferedEvent(
						rs.getLong("sequence_no"),
						rs.getString("event_uuid"),
						rs.getString("site_external_id"),
						rs.getString("lane_external_id"),
						rs.getString("device_external_id"),
						rs.getString("event_type"),
						rs.getString("payload"),
						rs.getString("attributes"),
						rs.getString("status"),
						rs.getInt("attempts"),
						rs.getString("last_error"),
						Utc.instantAt(rs, "received_at"),
						Utc.instantAt(rs, "dispatched_at"),
						Utc.instantAt(rs, "acked_at")));
	}

	/**
	 * The two timestamps below are the OWNING INSTANCE's clock, and that is
	 * deliberate rather than an overlooked shared-clock requirement.
	 *
	 * <p>The database clock is the reference for anything two instances
	 * must agree on". Nothing agrees on these: one instance owns a lane at a time,
	 * and {@code dispatched_at} / {@code acked_at} are its own bookkeeping. What
	 * two instances would have to agree on — the <em>order</em> of the buffer — is
	 * carried by {@code sequence_no}, which the database assigns.
	 *
	 * <p>They are bound as {@link java.sql.Timestamp} rather than {@link Instant}:
	 * the SQL Server driver has no binding for {@code java.time.Instant} and fails
	 * with a bare {@code AssertionError} out of the statement setter, which says
	 * nothing at all about what is wrong.
	 */
	public int markDispatched(List<Long> sequenceNumbers) {
		return update(sequenceNumbers, ScopedUpdate.table(TABLE)
				.set("status", BufferedEvent.DISPATCHED)
				.set("dispatched_at", now())
				.scopedBy(SCOPE_COLUMN));
	}

	public int markAcked(List<Long> sequenceNumbers) {
		return update(sequenceNumbers, ScopedUpdate.table(TABLE)
				.set("status", BufferedEvent.ACKED)
				.set("acked_at", now())
				.scopedBy(SCOPE_COLUMN));
	}

	/**
	 * ⚠️ {@link Utc}, not {@code Timestamp.from} — see that class. A zone-less
	 * conversion round-trips for values Java wrote and is wrong by the machine's UTC
	 * offset for values the database wrote, and this table has both.
	 */
	private static java.sql.Timestamp now() {
		return Utc.now();
	}

	/**
	 * Records a failed delivery attempt, and retires the event once it has had too
	 * many.
	 *
	 * <p><strong>Three set-based statements, and it used to be a read-write-back
	 * loop.</strong> The original seam could not express {@code attempts = attempts + 1},
	 * so it read every row and wrote each one back — which is slower, and
	 * worse than slower: two deliveries that both read {@code attempts = 3} and both
	 * write {@code 4} record <em>one</em> failure between them, and an event that has
	 * failed twice as often as its counter says is one that is retired later than the
	 * limit promises. {@link ScopedUpdate#increment} now performs the arithmetic in
	 * the database, where concurrent increments cannot be lost.
	 *
	 * <p>The retirement is applied <em>after</em> the increment and reads the value
	 * the increment produced. That ordering is the whole reason it is three
	 * statements rather than one: the new status depends on the new count, and a
	 * {@code CASE} expression over it would be caller-authored SQL in the one place
	 * the seam exists to keep it out of.
	 */
	public int recordFailure(List<Long> sequenceNumbers, int maxAttempts, String error) {
		if (sequenceNumbers.isEmpty()) {
			return 0;
		}
		String theseRows = "sequence_no IN (" + placeholders(sequenceNumbers.size()) + ")";
		Object[] ids = sequenceNumbers.toArray();

		int touched = seam.update(ScopedUpdate.table(TABLE)
				.set("last_error", truncate(error))
				.increment("attempts", 1)
				.scopedBy(SCOPE_COLUMN)
				.where(theseRows, ids));

		// Retired: too many attempts, and the count is now the true one. DEAD rather
		// than deleted, and the diagnostics endpoint reports it.
		seam.update(ScopedUpdate.table(TABLE)
				.set("status", BufferedEvent.DEAD)
				.scopedBy(SCOPE_COLUMN)
				.where(theseRows + " AND attempts >= ?", withMax(ids, maxAttempts)));

		// The rest go back into the queue.
		seam.update(ScopedUpdate.table(TABLE)
				.set("status", BufferedEvent.PENDING)
				.scopedBy(SCOPE_COLUMN)
				.where(theseRows + " AND attempts < ?", withMax(ids, maxAttempts)));

		return touched;
	}

	private static Object[] withMax(Object[] ids, int maxAttempts) {
		Object[] parameters = java.util.Arrays.copyOf(ids, ids.length + 1);
		parameters[ids.length] = maxAttempts;
		return parameters;
	}

	/**
	 * DEAD, not deleted.
	 *
	 * <p>The buffer is bounded and exhausted records are retired rather than
	 * removed. An event nobody could deliver is the one a site operator most needs
	 * to see, so it is retired into a status the diagnostics endpoint reports —
	 * never dropped, which would make a delivery failure indistinguishable from a
	 * capture that never happened.
	 */
	public int markDead(List<Long> sequenceNumbers, String error) {
		return update(sequenceNumbers, ScopedUpdate.table(TABLE)
				.set("status", BufferedEvent.DEAD)
				.set("last_error", truncate(error))
				.scopedBy(SCOPE_COLUMN));
	}

	public long countByStatus(String siteExternalId, String status) {
		return seam.count(ScopedSelect.from(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.where("status = ?", status));
	}

	public long depthOf(String siteExternalId, String laneExternalId) {
		return seam.count(ScopedSelect.from(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.where("lane_external_id = ? AND status IN ('PENDING', 'DISPATCHED')", laneExternalId));
	}

	/** One lane's count in one status. For {@code DEAD}, which is per lane in the diagnostics. */
	public long countByStatusOnLane(String laneExternalId, String status) {
		return seam.count(ScopedSelect.from(TABLE)
				.scopedBy(SCOPE_COLUMN)
				.where("lane_external_id = ? AND status = ?", laneExternalId, status));
	}

	/**
	 * When the oldest still-undelivered capture on this lane arrived, if there is one.
	 *
	 * <p><strong>Ordered by {@code sequence_no}, not by {@code received_at}</strong> —
	 * the same reason {@link #undelivered} is. The identity column is the buffer's
	 * order and two rows inside one millisecond have the same timestamp, so ordering
	 * by the clock would make "the oldest" ambiguous exactly when the lane is busiest.
	 *
	 * <p>A single-row read rather than a {@code MIN(…)}: the seam expresses no
	 * aggregate, and reaching around it for one would be a hole in the thing that
	 * makes every read scoped. With the index leading
	 * {@code (site_external_id, lane_external_id, sequence_no)} this is a seek and a
	 * single row.
	 */
	public Optional<Instant> oldestUndeliveredAt(String laneExternalId) {
		return seam.select(ScopedSelect.from(TABLE)
								.columns("received_at")
								.scopedBy(SCOPE_COLUMN)
								.where("lane_external_id = ? AND status IN ('PENDING', 'DISPATCHED')",
										laneExternalId)
								.orderBy("sequence_no")
								.limit(1),
						(rs, row) -> Utc.instantAt(rs, "received_at"))
				.stream()
				.findFirst();
	}

	// ------------------------------------------------------------------------

	private int update(List<Long> sequenceNumbers, ScopedUpdate update) {
		if (sequenceNumbers.isEmpty()) {
			return 0;
		}
		return seam.update(update.where(
				"sequence_no IN (" + placeholders(sequenceNumbers.size()) + ")", sequenceNumbers.toArray()));
	}

	private static String placeholders(int count) {
		return IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(", "));
	}

	private static String truncate(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= 1000 ? error : error.substring(0, 1000);
	}

}
