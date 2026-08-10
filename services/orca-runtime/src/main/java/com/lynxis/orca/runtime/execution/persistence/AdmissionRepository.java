package com.lynxis.orca.runtime.execution.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * Everything admission touches, through the scope seam and nothing else.
 *
 * <p>The first property proof used a {@code JdbcTemplate} inside
 * {@code src/integrationTest}; production cannot do that because
 * {@code ScopeSeamRule} fails such a dependency at build time. This repository is
 * the same three mechanisms — lane lock, filtered unique index and one transaction
 * — expressed through the seam.
 *
 * <p><strong>Admission required two additions to the seam:</strong>
 *
 * <ul>
 *   <li>{@link ScopedSelect#lockMatchedRows()} — the read half of a read-decide-write
 *       that two instances run at the same instant. Without a lock both read, both
 *       find nothing, and both start a visit.</li>
 *   <li>{@link ScopeSeam#insertReturningKey} — the visit's identity, from the
 *       insert itself. Reading it back afterwards is a correctness bug the moment
 *       two callers insert equal-looking rows.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class AdmissionRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	private final ScopeSeam seam;

	/**
	 * Resolves a lane external id to core's surrogate key, through core's published
	 * view.
	 *
	 * <p>This is a read through core's published view in runtime's own transaction:
	 * no network hop on the gate path and no access to anything core has not
	 * deliberately published. An
	 * external id this installation's site does not have resolves to empty — the
	 * scope predicate does that, not a check written here.
	 */
	public Optional<Long> laneIdOf(String laneExternalId) {
		return seam.select(ScopedSelect.from("core.topology_lane")
								.columns("lane_id")
								.scopedBy(SCOPE_COLUMN)
								.where("lane_external_id = ?", laneExternalId),
						(rs, row) -> rs.getLong("lane_id"))
				.stream().findFirst();
	}

	/** The reverse of {@link #laneIdOf}, returning the stable identifier used outside this service. */
	public Optional<String> laneExternalIdOf(long laneId) {
		return seam.select(ScopedSelect.from("core.topology_lane")
								.columns("lane_external_id")
								.scopedBy(SCOPE_COLUMN)
								.where("lane_id = ?", laneId),
						(rs, row) -> rs.getString("lane_external_id"))
				.stream().findFirst();
	}

	/**
	 * {@link #laneExternalIdOf} for many lanes at once.
	 *
	 * <p><strong>This exists because calling the single-lane version per row is a
	 * defect, not an inefficiency.</strong> A page of visits resolved one lane at a
	 * time issues one query per row — measured at 67 lookups for 66 rows before this
	 * method existed — and the cost scales with the page, so the largest permitted
	 * page is also the worst case. Lanes at a site are few and repeat heavily across
	 * rows, so the distinct set is small however long the page is.
	 *
	 * @return the mapping for the lanes this site actually publishes. A lane absent
	 *         from the result is one this scope cannot see; the caller decides what
	 *         that means rather than being handed a guess
	 */
	public java.util.Map<Long, String> laneExternalIdsOf(java.util.Collection<Long> laneIds) {
		java.util.Set<Long> distinct = new java.util.LinkedHashSet<>(laneIds);
		if (distinct.isEmpty()) {
			return java.util.Map.of();
		}

		// The placeholders are generated from the collection's size and the values go
		// through as parameters — the caller's data never becomes SQL text.
		StringBuilder in = new StringBuilder("lane_id IN (");
		for (int i = 0; i < distinct.size(); i++) {
			in.append(i == 0 ? "?" : ", ?");
		}
		in.append(')');

		java.util.Map<Long, String> byId = new java.util.HashMap<>();
		seam.select(ScopedSelect.from("core.topology_lane")
						.columns("lane_id", "lane_external_id")
						.scopedBy(SCOPE_COLUMN)
						.where(in.toString(), distinct.toArray()),
				(rs, row) -> byId.put(rs.getLong("lane_id"), rs.getString("lane_external_id")));
		return byId;
	}

	/**
	 * Creates the lane's admission row if it has none. Idempotent.
	 *
	 * <p>Called in its own transaction, before the one that admits. It is the row
	 * every admission for this lane will lock, so creating it inside the admitting
	 * transaction would mean the first two trucks on a fresh lane race on the
	 * <em>creation</em> rather than serialising on the row.
	 *
	 * @return whether this call created it
	 */
	public boolean createLaneSessionIfAbsent(long laneId, String siteExternalId, String laneExternalId) {
		if (laneSessionExists(laneId)) {
			return false;
		}
		try {
			seam.insert(ScopedInsert.into("lane_session")
					.scopedBy(SCOPE_COLUMN)
					.value("lane_id", laneId)
					.value(SCOPE_COLUMN, siteExternalId)
					.value("lane_external_id", laneExternalId));
			return true;
		}
		catch (org.springframework.dao.DuplicateKeyException alreadyThere) {
			// Two instances seeing a lane for the first time in the same instant. The
			// row is what was wanted and the row exists.
			return false;
		}
	}

	private boolean laneSessionExists(long laneId) {
		return seam.count(ScopedSelect.from("lane_session")
				.scopedBy(SCOPE_COLUMN)
				.where("lane_id = ?", laneId)) > 0;
	}

	/**
	 * Takes the lane.
	 *
	 * <p><strong>This is the mechanism the whole property rests on.</strong> An
	 * exclusive row lock on this lane's row and on no other, held to the end of the
	 * caller's transaction, so every admission for a lane serialises there — and a
	 * busy lane never blocks a quiet one.
	 *
	 * @return whether the lane has a session row at all. False means the caller
	 *         should create one and try again
	 */
	public boolean lockLane(long laneId) {
		return !seam.select(ScopedSelect.from("lane_session")
						.columns("lane_id")
						.scopedBy(SCOPE_COLUMN)
						.where("lane_id = ?", laneId)
						.lockMatchedRows(),
				(rs, row) -> rs.getLong("lane_id")).isEmpty();
	}

	/**
	 * The visit already running on this lane, if there is one.
	 *
	 * <p>Read under the lane lock, where it cannot race: whoever holds the lock is
	 * the only one who can be between reading this and inserting.
	 */
	public Optional<ActiveVisit> activeRootOn(long laneId) {
		List<ActiveVisit> running = seam.select(ScopedSelect.from("execution")
						.columns("execution_id", "external_id")
						.scopedBy(SCOPE_COLUMN)
						.where("lane_id = ? AND status = 'ACTIVE' AND parent_execution_id IS NULL", laneId),
				(rs, row) -> new ActiveVisit(rs.getLong("execution_id"), rs.getString("external_id")));
		return running.stream().findFirst();
	}

	/**
	 * Starts the visit row.
	 *
	 * @throws org.springframework.dao.DuplicateKeyException when the filtered unique
	 *         index refuses a second active root visit on this lane. The caller
	 *         correlates rather than failing — that path is unreachable while every
	 *         inbound path takes the lane lock, which is exactly why it is handled
	 */
	public long insertVisit(String externalId, String siteExternalId, long laneId, String plate) {
		return seam.insertReturningKey(ScopedInsert.into("execution")
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", externalId)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("lane_id", laneId)
				.value("status", "ACTIVE")
				.value("plate", plate), "execution_id");
	}

	public void recordProcessInstance(long executionId, String processInstanceId) {
		seam.update(ScopedUpdate.table("execution")
				.set("process_instance_id", processInstanceId)
				.scopedBy(SCOPE_COLUMN)
				.where("execution_id = ?", executionId));
	}

	/**
	 * Records which plate the lane is currently holding.
	 *
	 * @param bindingIt whether this is the plate that opened the session.
	 *                  {@code bound_at} moves only then: an event correlating to a
	 *                  running visit is not a new binding, and overwriting the
	 *                  timestamp would erase when the truck actually arrived
	 */
	public void bindLane(long laneId, String plate, boolean bindingIt) {
		ScopedUpdate update = ScopedUpdate.table("lane_session")
				.set("bound_plate", plate)
				.set("updated_at", now());
		if (bindingIt) {
			update.set("bound_at", now());
		}
		seam.update(update.scopedBy(SCOPE_COLUMN).where("lane_id = ?", laneId));
	}

	/**
	 * Attaches an accepted event to the visit it belongs to.
	 *
	 * <p>Both outcomes of admission land here — the event that started the visit and
	 * the event that joined one already running. Without this the second outcome is
	 * a decision with nowhere to land: acknowledged, and then existing nowhere.
	 */
	public void attachEvent(String eventUuid, String siteExternalId, long executionId, long laneId,
			String eventType, String deviceExternalId, String attributes, Instant occurredAt) {
		seam.insert(ScopedInsert.into("execution_event")
				.scopedBy(SCOPE_COLUMN)
				.value("event_uuid", eventUuid)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("execution_id", executionId)
				.value("lane_id", laneId)
				.value("event_type", eventType)
				.value("device_external_id", deviceExternalId)
				.value("attributes", attributes)
				.value("received_at", occurredAt == null ? now() : Timestamp.from(occurredAt)));
	}

	/**
	 * Finishes the visit, so the lane's filtered unique index will admit the next
	 * truck.
	 *
	 * @return how many rows moved. Zero means it had already finished — an ordinary
	 *         outcome for a replayed completion, and not a failure
	 */
	public int completeVisit(long executionId, String status) {
		return seam.update(ScopedUpdate.table("execution")
				.set("status", status)
				.set("completed_at", now())
				.scopedBy(SCOPE_COLUMN)
				.where("execution_id = ? AND status = 'ACTIVE'", executionId));
	}

	public Optional<VisitRow> visitByExternalId(String externalId) {
		return seam.select(ScopedSelect.from("execution")
						.columns("execution_id", "external_id", "lane_id", "status", "plate",
								"process_instance_id")
						.scopedBy(SCOPE_COLUMN)
						.where("external_id = ?", externalId),
				(rs, row) -> new VisitRow(rs.getLong("execution_id"), rs.getString("external_id"),
						rs.getLong("lane_id"), rs.getString("status"), rs.getString("plate"),
						rs.getString("process_instance_id"))).stream().findFirst();
	}

	public Optional<VisitRow> visitByProcessInstance(String processInstanceId) {
		return seam.select(ScopedSelect.from("execution")
						.columns("execution_id", "external_id", "lane_id", "status", "plate",
								"process_instance_id")
						.scopedBy(SCOPE_COLUMN)
						.where("process_instance_id = ?", processInstanceId),
				(rs, row) -> new VisitRow(rs.getLong("execution_id"), rs.getString("external_id"),
						rs.getLong("lane_id"), rs.getString("status"), rs.getString("plate"),
						rs.getString("process_instance_id"))).stream().findFirst();
	}

	/**
	 * Bound as {@link Timestamp} rather than {@link Instant}: the SQL Server driver
	 * has no binding for {@code java.time.Instant} and fails with a bare
	 * {@code AssertionError} out of the statement setter, which says nothing at all
	 * about what is wrong.
	 */
	private static Timestamp now() {
		return Timestamp.from(Instant.now());
	}

	/** A visit already running on a lane — only what correlation needs. */
	public record ActiveVisit(long executionId, String externalId) {
	}

	public record VisitRow(long executionId, String externalId, long laneId, String status, String plate,
			String processInstanceId) {
	}
}
