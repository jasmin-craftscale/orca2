package com.lynxis.orca.runtime.workitem.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * The tables {@code V115__work_items.sql} creates, declared where the build check
 * can read them.
 *
 * <p>Both are {@link Growth#TRAFFIC_GROWING}: one item per exception per truck,
 * and several audit rows per item, forever, at every lane. Growth of the legacy
 * 1.x {@code work_items} table was never measured; this implementation declares
 * the retention class from its first migration instead.
 */
public final class WorkItemTables {

	private WorkItemTables() {
	}

	/**
	 * One unit of human work, parked on one engine task.
	 *
	 * <p>⚠️ The retention class name is <strong>PROVISIONAL</strong>.
	 * {@code RetentionClassRule} fails the build unless a class is <em>named</em>;
	 * it cannot validate membership in the unreconciled closed catalog.
	 *
	 * @param status  {@code QUEUED} · {@code IN_PROGRESS} · {@code COMPLETED} ·
	 *                {@code FAILED} — exactly the four with a writer.
	 *                The dead 1.x escalation statuses are deliberately absent
	 * @param taskId  the engine's handle for the wait state this item parks on.
	 *                Completion presents it back to the engine, which is what makes
	 *                an out-of-order submit refusable rather than silently applied
	 */
	@PersistentTable(name = "work_item", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("work_item") // PROVISIONAL — see above
	public record WorkItem(
			long workItemId,
			String externalId,
			String siteExternalId,
			long executionId,
			long laneId,
			String visitExternalId,
			String laneExternalId,
			String processInstanceId,
			String taskId,
			String processDefinitionKey,
			String nodeReference,
			String screenExternalId,
			String status,
			String assignee,
			Instant queuedAt,
			Instant startedAt,
			Instant completedAt,
			Integer completionDurationSec,
			Instant slaBreachedAt,
			String eventData,
			String correctedEventData) {

		public static final String QUEUED = "QUEUED";
		public static final String IN_PROGRESS = "IN_PROGRESS";
		public static final String COMPLETED = "COMPLETED";

		/** Lane reset is the writer; it fails the visit and its open items together. */
		public static final String FAILED = "FAILED";
	}

	/**
	 * One auditable action on one work item.
	 *
	 * <p>Retention class {@code audit}, PROVISIONAL, deliberately the same name
	 * core's {@code audit_event} and the settings history use: these are all
	 * records of what people did, and two different classes for one kind of fact
	 * would let one outlive the other.
	 */
	@PersistentTable(name = "work_item_audit", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("audit") // PROVISIONAL
	public record WorkItemAudit(
			long workItemAuditId,
			String siteExternalId,
			long workItemId,
			String action,
			String actor,
			String previousAssignee,
			Instant occurredAt,
			Integer processingDurationSec,
			Integer elapsedSec) {

		public static final String TAKE = "TAKE";
		public static final String TAKE_OVER = "TAKE_OVER";
		public static final String PARK = "PARK";
		public static final String ASSIGN = "ASSIGN";
		public static final String COMPLETE = "COMPLETE";
		public static final String FAIL = "FAIL";
		public static final String SLA_BREACH = "SLA_BREACH";
	}
}
