package com.lynxis.orca.runtime.workitem.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;
import com.lynxis.orca.runtime.persistence.Utc;
import com.lynxis.orca.runtime.workitem.domain.WorkItemTables.WorkItem;
import com.lynxis.orca.runtime.workitem.domain.WorkItemTables.WorkItemAudit;

import lombok.RequiredArgsConstructor;

/**
 * Everything the work-item lifecycle touches, through the scope seam and nothing
 * else.
 *
 * <p><strong>Every state transition here is a conditional UPDATE guarded by
 * rows-affected</strong>, the same primitive used by the lease and idempotency
 * claim. Only that update prevents a double claim; pre-checks improve the
 * interface but are racy and must never be the guard. A method returning
 * {@code 0} is telling the caller it lost;
 * translating that into a typed conflict is the service's job, and translating it
 * into a silent no-op is the 1.x defect this phase exists not to port.
 */
@RequiredArgsConstructor
public class WorkItemRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	private static final String[] COLUMNS = { "work_item_id", "external_id", "site_external_id",
			"execution_id", "lane_id", "visit_external_id", "lane_external_id", "process_instance_id",
			"task_id", "process_definition_key", "node_reference", "screen_external_id", "status",
			"assignee", "queued_at", "started_at", "completed_at", "completion_duration_sec",
			"sla_breached_at", "event_data", "corrected_event_data" };

	private final ScopeSeam seam;

	public long insert(String externalId, String siteExternalId, long executionId, long laneId,
			String visitExternalId, String laneExternalId, String processInstanceId, String taskId,
			String processDefinitionKey, String nodeReference, String screenExternalId, String eventData) {
		return seam.insertReturningKey(ScopedInsert.into("work_item")
				.scopedBy(SCOPE_COLUMN)
				.value("external_id", externalId)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("execution_id", executionId)
				.value("lane_id", laneId)
				.value("visit_external_id", visitExternalId)
				.value("lane_external_id", laneExternalId)
				.value("process_instance_id", processInstanceId)
				.value("task_id", taskId)
				.value("process_definition_key", processDefinitionKey)
				.value("node_reference", nodeReference)
				.value("screen_external_id", screenExternalId)
				.value("status", WorkItem.QUEUED)
				.value("event_data", eventData), "work_item_id");
	}

	public Optional<WorkItem> byExternalId(String externalId) {
		return one(ScopedSelect.from("work_item")
				.columns(COLUMNS)
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ?", externalId));
	}

	public Optional<WorkItem> byTaskId(String taskId) {
		return one(ScopedSelect.from("work_item")
				.columns(COLUMNS)
				.scopedBy(SCOPE_COLUMN)
				.where("task_id = ?", taskId));
	}

	/**
	 * The queue, oldest first. The grid read layers routing priority over this;
	 * FIFO by {@code queued_at} remains the tiebreak.
	 *
	 * <p><strong>{@code status == null} means the OPEN QUEUE, in the SQL itself.</strong>
	 * The predicate has to live here rather than in the caller's post-filter: this
	 * table is traffic-growing and the read is ordered oldest-first, so on any
	 * mature site an unpredicated fetch returns nothing but ancient terminal rows
	 * and the open items — necessarily newer — fall outside every cap. Found by
	 * the pre-handover review; the grid semantics did not change, the query now
	 * actually implements them.
	 */
	public List<WorkItem> list(String status, String laneExternalId, String assignee, int limit) {
		StringBuilder where = new StringBuilder("1 = 1");
		List<Object> parameters = new ArrayList<>();
		if (status != null) {
			where.append(" AND status = ?");
			parameters.add(status);
		}
		else {
			where.append(" AND status IN ('QUEUED', 'IN_PROGRESS')");
		}
		if (laneExternalId != null) {
			where.append(" AND lane_external_id = ?");
			parameters.add(laneExternalId);
		}
		if (assignee != null) {
			where.append(" AND assignee = ?");
			parameters.add(assignee);
		}
		return seam.select(ScopedSelect.from("work_item")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where(where.toString(), parameters.toArray())
						.orderBy("queued_at")
						.limit(limit),
				(rs, row) -> map(rs));
	}

	/**
	 * Every item of the instance, terminal ones included — the SLA recording read.
	 * Deliberately NOT filtered to open statuses: a timer that fired is a fact,
	 * and the recording job may run after the operator completed the item (the
	 * executor's acquire interval is seconds); an open-only read would silently
	 * drop exactly the borderline breaches the statistics exist to count.
	 */
	public List<WorkItem> itemsOfProcessInstance(String processInstanceId) {
		return seam.select(ScopedSelect.from("work_item")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("process_instance_id = ?", processInstanceId),
				(rs, row) -> map(rs));
	}

	public List<WorkItem> openItemsOf(long executionId) {
		return seam.select(ScopedSelect.from("work_item")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("execution_id = ? AND status IN ('QUEUED', 'IN_PROGRESS')", executionId),
				(rs, row) -> map(rs));
	}

	/**
	 * The claim. {@code QUEUED} → {@code IN_PROGRESS}, one winner.
	 *
	 * <p>A pre-assigned item is claimable by its assignee only; an unassigned item
	 * by anyone who passes the service's eligibility check. The predicate carries
	 * both assignment cases, and the
	 * conditional UPDATE is the only guard.
	 *
	 * @return whether this caller won
	 */
	public boolean take(String externalId, String actor, Instant now) {
		return seam.update(ScopedUpdate.table("work_item")
				.set("status", WorkItem.IN_PROGRESS)
				.set("assignee", actor)
				.set("started_at", Utc.timestampOf(now))
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ? AND status = 'QUEUED' AND (assignee IS NULL OR assignee = ?)",
						externalId, actor)) == 1;
	}

	/**
	 * The supervisor steal. It stays {@code IN_PROGRESS}, reassigns the item and
	 * resets the clock. Guarded on the previous holder too, so the audit row's
	 * {@code previous_assignee} is the person it actually happened to — if they
	 * completed or parked between the read and this update, rows-affected is 0 and
	 * the caller is told, not fooled.
	 */
	public boolean takeover(String externalId, String actor, String previousAssignee, Instant now) {
		return seam.update(ScopedUpdate.table("work_item")
				.set("assignee", actor)
				.set("started_at", Utc.timestampOf(now))
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ? AND status = 'IN_PROGRESS' AND assignee = ?",
						externalId, previousAssignee)) == 1;
	}

	/**
	 * Park is re-queue: {@code IN_PROGRESS} → {@code QUEUED}, assignee and clock
	 * cleared. The 1.x shape is kept deliberately: there is no distinct PARKED
	 * status, so parked work returns to the visible queue rather than acquiring a
	 * second waiting state.
	 */
	public boolean park(String externalId, String actor) {
		return seam.update(ScopedUpdate.table("work_item")
				.set("status", WorkItem.QUEUED)
				.set("assignee", null)
				.set("started_at", null)
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ? AND status = 'IN_PROGRESS' AND assignee = ?",
						externalId, actor)) == 1;
	}

	/** Pre-assign: names an operator, stays {@code QUEUED} — the assignee still must take. */
	public boolean assign(String externalId, String assignee) {
		return seam.update(ScopedUpdate.table("work_item")
				.set("assignee", assignee)
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ? AND status = 'QUEUED'", externalId)) == 1;
	}

	/**
	 * The item's half of complete-and-advance. {@code completion_duration_sec} is
	 * computed by the caller from the row's own {@code started_at}, never taken
	 * from the request, and the guard on the assignee is what makes
	 * that read safe: a takeover between the read and this update changes the
	 * assignee, so this returns 0 and the completion is refused whole.
	 */
	public boolean complete(String externalId, String actor, Instant now, int completionDurationSec,
			String correctedEventData) {
		return seam.update(ScopedUpdate.table("work_item")
				.set("status", WorkItem.COMPLETED)
				.set("completed_at", Utc.timestampOf(now))
				.set("completion_duration_sec", completionDurationSec)
				.set("corrected_event_data", correctedEventData)
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ? AND status = 'IN_PROGRESS' AND assignee = ?",
						externalId, actor)) == 1;
	}

	/** Lane reset's per-item write. Guarded like every other transition. */
	public boolean fail(long workItemId) {
		return seam.update(ScopedUpdate.table("work_item")
				.set("status", WorkItem.FAILED)
				.set("completed_at", Utc.now())
				.scopedBy(SCOPE_COLUMN)
				.where("work_item_id = ? AND status IN ('QUEUED', 'IN_PROGRESS')", workItemId)) == 1;
	}

	/**
	 * Records an SLA breach. {@code sla_breached_at IS NULL} in the predicate is what
	 * makes "fires once across a restart" a database fact rather than a hope: a
	 * timer job retried after a crash records nothing the second time.
	 *
	 * @return whether this call recorded it (false = already recorded)
	 */
	public boolean recordBreach(String taskId, Instant now) {
		return seam.update(ScopedUpdate.table("work_item")
				.set("sla_breached_at", Utc.timestampOf(now))
				.scopedBy(SCOPE_COLUMN)
				.where("task_id = ? AND sla_breached_at IS NULL", taskId)) == 1;
	}

	// --- the trail ----------------------------------------------------------

	public void audit(long workItemId, String siteExternalId, String action, String actor,
			String previousAssignee, Integer processingDurationSec, Integer elapsedSec) {
		seam.insert(ScopedInsert.into("work_item_audit")
				.scopedBy(SCOPE_COLUMN)
				.value(SCOPE_COLUMN, siteExternalId)
				.value("work_item_id", workItemId)
				.value("action", action)
				.value("actor", actor)
				.value("previous_assignee", previousAssignee)
				.value("processing_duration_sec", processingDurationSec)
				.value("elapsed_sec", elapsedSec));
	}

	public List<WorkItemAudit> auditTrail(long workItemId) {
		return seam.select(ScopedSelect.from("work_item_audit")
						.columns("work_item_audit_id", "site_external_id", "work_item_id", "action", "actor",
								"previous_assignee", "occurred_at", "processing_duration_sec", "elapsed_sec")
						.scopedBy(SCOPE_COLUMN)
						.where("work_item_id = ?", workItemId)
						.orderBy("occurred_at"),
				(rs, row) -> new WorkItemAudit(
						rs.getLong("work_item_audit_id"),
						rs.getString("site_external_id"),
						rs.getLong("work_item_id"),
						rs.getString("action"),
						rs.getString("actor"),
						rs.getString("previous_assignee"),
						Utc.instantAt(rs, "occurred_at"),
						integer(rs, "processing_duration_sec"),
						integer(rs, "elapsed_sec")));
	}

	// ------------------------------------------------------------------------

	private Optional<WorkItem> one(ScopedSelect select) {
		return seam.select(select, (rs, row) -> map(rs)).stream().findFirst();
	}

	private static WorkItem map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new WorkItem(
				rs.getLong("work_item_id"),
				rs.getString("external_id"),
				rs.getString("site_external_id"),
				rs.getLong("execution_id"),
				rs.getLong("lane_id"),
				rs.getString("visit_external_id"),
				rs.getString("lane_external_id"),
				rs.getString("process_instance_id"),
				rs.getString("task_id"),
				rs.getString("process_definition_key"),
				rs.getString("node_reference"),
				rs.getString("screen_external_id"),
				rs.getString("status"),
				rs.getString("assignee"),
				Utc.instantAt(rs, "queued_at"),
				Utc.instantAt(rs, "started_at"),
				Utc.instantAt(rs, "completed_at"),
				integer(rs, "completion_duration_sec"),
				Utc.instantAt(rs, "sla_breached_at"),
				rs.getString("event_data"),
				rs.getString("corrected_event_data"));
	}

	private static Integer integer(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}
}
