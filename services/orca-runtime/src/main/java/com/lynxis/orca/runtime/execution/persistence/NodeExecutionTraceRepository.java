package com.lynxis.orca.runtime.execution.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * The visit's step trace, through the scope seam and nothing else.
 *
 * <p>Writers are the engine-event recorder and the delegates; the reader is the
 * selector data provider, whose questions are always of one shape: <em>the newest
 * row for this node in this visit</em> — a visit that re-enters a node answers
 * with the latest pass. The index the migration created exists for exactly that
 * read, and no other read of this table should be added without extending it.
 */
@RequiredArgsConstructor
public class NodeExecutionTraceRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	private final ScopeSeam seam;

	/** Records that the process entered a node, and returns the trace row's key. */
	public long recordEntered(String externalId, String siteExternalId, long executionId,
			String nodeUuid, String nodeType, Instant enteredAt) {
		return seam.insertReturningKey(ScopedInsert.into("node_execution")
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", externalId)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("execution_id", executionId)
				.value("node_uuid", nodeUuid)
				.value("node_type", nodeType)
				.value("status", "ENTERED")
				.value("entered_at", Timestamp.from(enteredAt)), "node_execution_id");
	}

	/**
	 * Marks a step's outcome. Zero rows changed is an ordinary answer — the
	 * engine can report a cancellation for a step this service never recorded,
	 * and inventing a row for it after the fact would be a trace that lies.
	 */
	public int recordOutcome(long nodeExecutionId, String status, Instant completedAt) {
		return seam.update(ScopedUpdate.table("node_execution")
				.scopedBy(SCOPE_COLUMN)
				.set("status", status)
				.set("completed_at", Timestamp.from(completedAt))
				.where("node_execution_id = ?", nodeExecutionId));
	}

	/** Attaches what the step saw — JSON text, typed by whoever reads it back. */
	public int recordPayload(long nodeExecutionId, String payloadJson) {
		return seam.update(ScopedUpdate.table("node_execution")
				.scopedBy(SCOPE_COLUMN)
				.set("execution_payload", payloadJson)
				.where("node_execution_id = ?", nodeExecutionId));
	}

	/** The newest payload for this node in this visit, or empty if the node never ran. */
	public Optional<String> latestPayload(long executionId, String nodeUuid) {
		return seam.select(ScopedSelect.from("node_execution")
						.columns("execution_payload")
						.scopedBy(SCOPE_COLUMN)
						.where("execution_id = ? AND node_uuid = ?", executionId, nodeUuid)
						.orderByDescending("node_execution_id")
						.limit(1),
				(rs, row) -> rs.getString("execution_payload"))
				.stream().findFirst();
	}

	/**
	 * Records a step that the engine reports as completed — one row carrying both
	 * timestamps and whatever payload the producing delegate left aside. The trace
	 * is written on completion because that is the moment the engine's event is
	 * certain; a step that never completes is the engine's story to tell, not a
	 * phantom row here.
	 */
	public long recordCompletedStep(String externalId, String siteExternalId, long executionId,
			String nodeUuid, String nodeType, String payloadJson, Instant at) {
		return seam.insertReturningKey(ScopedInsert.into("node_execution")
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", externalId)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("execution_id", executionId)
				.value("node_uuid", nodeUuid)
				.value("node_type", nodeType)
				.value("status", "COMPLETED")
				.value("execution_payload", payloadJson)
				.value("entered_at", Timestamp.from(at))
				.value("completed_at", Timestamp.from(at)), "node_execution_id");
	}

	/** The visit a process instance belongs to: its key and lane, for trace and child rows. */
	public Optional<VisitRef> visitByEngineInstance(String processInstanceId) {
		return seam.select(ScopedSelect.from("execution")
						.columns("execution_id", "lane_id")
						.scopedBy(SCOPE_COLUMN)
						.where("process_instance_id = ?", processInstanceId),
				(rs, row) -> new VisitRef(rs.getLong("execution_id"), rs.getLong("lane_id")))
				.stream().findFirst();
	}

	/**
	 * A child execution: a subflow or iterator instance, sharing its parent's lane
	 * and pointing at it. Roots are admission's to create, never this method's.
	 */
	public long insertChildExecution(String externalId, String siteExternalId, long laneId,
			long parentExecutionId, Long workflowId, Integer definitionVersion,
			String processInstanceId) {
		return seam.insertReturningKey(ScopedInsert.into("execution")
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", externalId)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("lane_id", laneId)
				.value("parent_execution_id", parentExecutionId)
				.value("status", "ACTIVE")
				.value("workflow_id", workflowId)
				.value("definition_version", definitionVersion)
				.value("process_instance_id", processInstanceId), "execution_id");
	}

	/**
	 * Closes a CHILD execution when its engine instance ends. Root visits are
	 * deliberately excluded by the predicate — closing those, with the outbound
	 * fact that closing implies, belongs to the visit-completion path.
	 */
	public int completeChildByEngineInstance(String processInstanceId, Instant at) {
		return seam.update(ScopedUpdate.table("execution")
				.scopedBy(SCOPE_COLUMN)
				.set("status", "COMPLETED")
				.set("completed_at", Timestamp.from(at))
				.where("process_instance_id = ? AND parent_execution_id IS NOT NULL", processInstanceId));
	}

	/** The visit's key and lane. */
	public record VisitRef(long executionId, long laneId) {
	}
}
