package com.lynxis.orca.runtime.workitem.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.runtime.execution.api.ManualStepPort;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;
import com.lynxis.orca.runtime.workitem.domain.WorkItemTables.WorkItem;
import com.lynxis.orca.runtime.workitem.domain.WorkItemTables.WorkItemAudit;
import com.lynxis.orca.runtime.workitem.persistence.WorkItemRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The work-item lifecycle — §C2's state diagram, with the three inversions of
 * {@code docs/work-items-schema-from-1x.md} §0 built in rather than aspired to:
 *
 * <ol>
 *   <li><strong>Creation happens in the engine's transaction.</strong>
 *       {@link #manualStepReached} is called by the execution module's task
 *       listener while the engine is parking, so the item and the wait state
 *       commit together — see {@code WorkItemCreationListener} for the other
 *       half.</li>
 *   <li><strong>Completion advances the process, atomically.</strong>
 *       {@link #complete} runs the guarded item update and
 *       {@link ManualStepPort#completeManualStep} in one transaction; either
 *       both happen or neither.</li>
 *   <li><strong>An out-of-order submit is refused.</strong> The engine holding
 *       no such open task throws out of the same transaction, so the item
 *       update rolls back with it and the caller gets a typed refusal.</li>
 * </ol>
 *
 * <p>Every transition is a conditional UPDATE guarded by rows-affected; a loser
 * receives a <em>typed conflict carrying the current state</em>, never a silent
 * no-op. 1.x's pre-checks stay what they were — interface conveniences — and are
 * not the guard.
 */
@Slf4j
public class WorkItemService implements WorkItemIntake {

	private final WorkItemRepository repository;
	private final ManualStepPort manualSteps;
	private final TransactionTemplate transactions;
	private final String siteExternalId;

	public WorkItemService(WorkItemRepository repository, ManualStepPort manualSteps,
			TransactionTemplate transactions, String siteExternalId) {
		this.repository = repository;
		this.manualSteps = manualSteps;
		this.transactions = transactions;
		this.siteExternalId = siteExternalId;
	}

	// --- intake: the engine's side of the wall ------------------------------

	/**
	 * {@inheritDoc}
	 *
	 * <p>Joins the caller's (the engine's) transaction —
	 * {@code PROPAGATION_REQUIRED} on a transaction that already exists adds
	 * nothing, and that is the point: this must never commit separately.
	 */
	@Override
	public void manualStepReached(ManualStep step) {
		transactions.executeWithoutResult(status -> {
			String externalId = "wi-" + UUID.randomUUID();
			repository.insert(externalId, siteExternalId, step.executionId(), step.laneId(),
					step.visitExternalId(), step.laneExternalId(), step.processInstanceId(), step.taskId(),
					step.processDefinitionKey(), step.nodeReference(), null, step.eventData());
			log.info("work item {} queued for visit {} at node '{}' (task {})", externalId,
					step.visitExternalId(), step.nodeReference(), step.taskId());
		});
	}

	@Override
	public int failOpenItemsFor(long executionId, String actor) {
		return transactions.execute(status -> {
			List<WorkItem> open = repository.openItemsOf(executionId);
			int failed = 0;
			for (WorkItem item : open) {
				if (repository.fail(item.workItemId())) {
					repository.audit(item.workItemId(), siteExternalId, WorkItemAudit.FAIL, actor,
							item.assignee(), null, elapsedSince(item.queuedAt()));
					failed++;
				}
			}
			return failed;
		});
	}

	// --- the operator actions ----------------------------------------------

	/** The guarded claim. The loser is told what the item is now, never silently no-op'd. */
	public WorkItem take(String externalId, String actor) {
		return transactions.execute(status -> {
			WorkItem before = require(externalId);
			if (!repository.take(externalId, actor, Instant.now())) {
				throw conflict(externalId, "take");
			}
			repository.audit(before.workItemId(), siteExternalId, WorkItemAudit.TAKE, actor, null,
					null, elapsedSince(before.queuedAt()));
			return require(externalId);
		});
	}

	/**
	 * The supervisor steal: reassigns and resets the clock, stays
	 * {@code IN_PROGRESS}. The read before the guarded update supplies the audit
	 * row's {@code previous_assignee}; the guard re-checks it, so a holder who
	 * completed or parked in between turns this into a conflict, not a lie.
	 */
	public WorkItem takeover(String externalId, String actor) {
		return transactions.execute(status -> {
			WorkItem before = require(externalId);
			String previous = before.assignee();
			if (previous == null || !WorkItem.IN_PROGRESS.equals(before.status())
					|| !repository.takeover(externalId, actor, previous, Instant.now())) {
				throw conflict(externalId, "takeover");
			}
			repository.audit(before.workItemId(), siteExternalId, WorkItemAudit.TAKE_OVER, actor,
					previous, secondsBetween(before.startedAt(), Instant.now()),
					elapsedSince(before.queuedAt()));
			return require(externalId);
		});
	}

	/** Park is re-queue (the stated design choice — no PARKED status): holder returns it, clock cleared. */
	public WorkItem park(String externalId, String actor) {
		return transactions.execute(status -> {
			WorkItem before = require(externalId);
			if (!repository.park(externalId, actor)) {
				throw conflict(externalId, "park");
			}
			repository.audit(before.workItemId(), siteExternalId, WorkItemAudit.PARK, actor, actor,
					secondsBetween(before.startedAt(), Instant.now()), elapsedSince(before.queuedAt()));
			return require(externalId);
		});
	}

	/** Pre-assign: names an operator, stays {@code QUEUED}. The assignee still must take. */
	public WorkItem assign(String externalId, String assignee, String actor) {
		return transactions.execute(status -> {
			WorkItem before = require(externalId);
			if (!repository.assign(externalId, assignee)) {
				throw conflict(externalId, "assign");
			}
			repository.audit(before.workItemId(), siteExternalId, WorkItemAudit.ASSIGN, actor,
					before.assignee(), null, elapsedSince(before.queuedAt()));
			return require(externalId);
		});
	}

	/**
	 * <strong>Inversions 1 and 3, in one method.</strong> Completes the item AND
	 * advances the parked process in one transaction; a submit for a step the
	 * engine is not waiting on is refused and nothing moves.
	 *
	 * <p>{@code completion_duration_sec} is computed here from the row's own
	 * {@code started_at} — the request cannot supply it (sheet §1). The read is
	 * safe because the guarded update re-checks holder and status: a takeover in
	 * between makes rows-affected 0 and the whole completion a conflict.
	 */
	public WorkItem complete(String externalId, String actor, String correctedEventData) {
		return transactions.execute(status -> {
			WorkItem before = require(externalId);
			Instant now = Instant.now();
			Integer duration = secondsBetween(before.startedAt(), now);
			if (duration == null
					|| !repository.complete(externalId, actor, now, duration, correctedEventData)) {
				throw conflict(externalId, "complete");
			}

			// The engine, in the SAME transaction. Not after the commit: between
			// the two there is a state where the console believes the item is done
			// and the engine does not — the exact 1.x shape §0 inverts. If the
			// engine is not waiting on this task, ProcessNotWaitingException
			// unwinds this transaction and the item update above with it.
			manualSteps.completeManualStep(before.taskId());

			repository.audit(before.workItemId(), siteExternalId, WorkItemAudit.COMPLETE, actor, null,
					duration, elapsedSince(before.queuedAt()));

			log.info("work item {} completed by {}; process {} advanced in the same transaction",
					externalId, actor, before.processInstanceId());
			return require(externalId);
		});
	}

	// --- WP3: the timer's record --------------------------------------------

	/**
	 * The SLA timer fired for the wait state this task id names.
	 *
	 * <p>Idempotent by predicate ({@code sla_breached_at IS NULL}), which is what
	 * "fires once across a restart" rests on: a timer job that is retried after a
	 * crash finds the record already written and writes nothing.
	 */
	public void recordSlaBreach(String taskId) {
		transactions.executeWithoutResult(status -> {
			if (!repository.recordBreach(taskId, Instant.now())) {
				log.debug("SLA breach for task {} was already recorded; not recording twice", taskId);
				return;
			}
			WorkItem item = repository.byTaskId(taskId).orElseThrow(() -> new IllegalStateException(
					"recordBreach moved a row for task " + taskId + " but no work item reads back. "
							+ "The scope predicate and the update disagree, which should be impossible."));
			repository.audit(item.workItemId(), siteExternalId, WorkItemAudit.SLA_BREACH,
					"system:sla-timer", item.assignee(), null, elapsedSince(item.queuedAt()));
			log.warn("work item {} breached its SLA (queued {}, assignee {})", item.externalId(),
					item.queuedAt(), item.assignee());
		});
	}

	// --- reads --------------------------------------------------------------

	public WorkItem get(String externalId) {
		return require(externalId);
	}

	public List<WorkItem> list(String status, String laneExternalId, String assignee, int limit) {
		return repository.list(status, laneExternalId, assignee, limit);
	}

	public List<WorkItemAudit> auditTrail(String externalId) {
		return repository.auditTrail(require(externalId).workItemId());
	}

	// ------------------------------------------------------------------------

	private WorkItem require(String externalId) {
		return repository.byExternalId(externalId)
				.orElseThrow(() -> new WorkItemNotFoundException(externalId));
	}

	/** The loser's answer carries the item's current state — a conflict a caller can act on. */
	private WorkItemConflictException conflict(String externalId, String action) {
		WorkItem now = repository.byExternalId(externalId).orElse(null);
		return new WorkItemConflictException(externalId, action,
				now == null ? "GONE" : now.status(), now == null ? null : now.assignee());
	}

	private static Integer elapsedSince(Instant queuedAt) {
		return secondsBetween(queuedAt, Instant.now());
	}

	private static Integer secondsBetween(Instant from, Instant to) {
		if (from == null || to == null) {
			return null;
		}
		return (int) Duration.between(from, to).toSeconds();
	}

	/** No such item under this scope. 404, not 500. */
	public static class WorkItemNotFoundException extends RuntimeException {

		public WorkItemNotFoundException(String externalId) {
			super("No work item '" + externalId + "' exists under this installation's scope.");
		}
	}

	/**
	 * The guarded update moved nothing — somebody else got there first, or the
	 * item is not in a state this action applies to. Typed, with the current
	 * state, so the console can show the operator what actually happened instead
	 * of a stale grid row.
	 */
	public static class WorkItemConflictException extends RuntimeException {

		private final String currentStatus;
		private final String currentAssignee;

		public WorkItemConflictException(String externalId, String action, String currentStatus,
				String currentAssignee) {
			super("Work item '" + externalId + "' did not accept '" + action + "': it is now "
					+ currentStatus + (currentAssignee == null ? ", unassigned"
							: ", held by " + currentAssignee)
					+ ". Somebody else acted first — the conditional update is the guard, and it moved 0 rows.");
			this.currentStatus = currentStatus;
			this.currentAssignee = currentAssignee;
		}

		public String currentStatus() {
			return currentStatus;
		}

		public String currentAssignee() {
			return currentAssignee;
		}
	}
}
