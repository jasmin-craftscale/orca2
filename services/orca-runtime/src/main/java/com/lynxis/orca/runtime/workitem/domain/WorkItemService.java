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
import com.lynxis.orca.runtime.workitem.persistence.RoutingReadRepository;
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
	private final RoutingReadRepository routing;
	private final PresenceService presence;
	private final ManualStepPort manualSteps;
	private final TransactionTemplate transactions;
	private final String siteExternalId;

	public WorkItemService(WorkItemRepository repository, RoutingReadRepository routing,
			PresenceService presence, ManualStepPort manualSteps, TransactionTemplate transactions,
			String siteExternalId) {
		this.repository = repository;
		this.routing = routing;
		this.presence = presence;
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
			// The screen identity this node fronts, resolved at creation (WP2). An
			// unconfigured node still creates an item — a screen-less item is
			// visible and claimable by anyone, which beats invisible human work.
			String screenExternalId = routing
					.screenFor(step.processDefinitionKey(), step.nodeReference())
					.map(RoutingReadRepository.ScreenIdentity::screenExternalId)
					.orElse(null);

			String externalId = "wi-" + UUID.randomUUID();
			long workItemId = repository.insert(externalId, siteExternalId, step.executionId(),
					step.laneId(), step.visitExternalId(), step.laneExternalId(),
					step.processInstanceId(), step.taskId(), step.processDefinitionKey(),
					step.nodeReference(), screenExternalId, step.eventData());
			log.info("work item {} queued for visit {} at node '{}' (task {}, screen {})", externalId,
					step.visitExternalId(), step.nodeReference(), step.taskId(), screenExternalId);

			// WP4: the Push half of the routing evaluation, in the same
			// transaction. Push = PRE-ASSIGN to an assignable eligible operator —
			// the item stays QUEUED and the assignee still takes it (sheet §1);
			// Prompt teams broadcast, which is the notify hub's, later. No
			// assignable operator anywhere = the item stays unassigned and
			// visible, never parked on someone who cannot act.
			if (screenExternalId != null) {
				pushAssign(workItemId, externalId, screenExternalId, step.laneExternalId());
			}
		});
	}

	/**
	 * PUSH rules in deterministic order — priority set before unset, lower first,
	 * team external id as the total-order tiebreak; the first team with an
	 * assignable member wins. The same ordering family as the grid's, which is
	 * the point: 1.x's push path iterated a Go map and disagreed with its own
	 * grid ordering per run.
	 */
	private void pushAssign(long workItemId, String externalId, String screenExternalId,
			String laneExternalId) {
		List<RoutingReadRepository.RouteRule> pushRules =
				routing.rulesFor(screenExternalId, laneExternalId).stream()
						.filter(rule -> "PUSH".equals(rule.handlingMethod()))
						.sorted(java.util.Comparator
								.comparing((RoutingReadRepository.RouteRule rule) ->
										rule.priority() == null ? 1 : 0)
								.thenComparing(rule -> rule.priority() == null
										? Integer.MAX_VALUE : rule.priority())
								.thenComparing(RoutingReadRepository.RouteRule::teamExternalId))
						.toList();
		for (RoutingReadRepository.RouteRule rule : pushRules) {
			java.util.Optional<String> operator = presence.selectAssignable(
					new java.util.HashSet<>(routing.membersOf(rule.teamExternalId())));
			if (operator.isPresent()) {
				repository.assign(externalId, operator.get());
				repository.audit(workItemId, siteExternalId, WorkItemAudit.ASSIGN, "system:router",
						null, null, 0);
				log.info("work item {} pushed to {} of team {}", externalId, operator.get(),
						rule.teamExternalId());
				return;
			}
		}
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

	/**
	 * The guarded claim. The loser is told what the item is now, never silently
	 * no-op'd — and an operator outside the eligible teams is refused <em>before</em>
	 * the guard (WP2: the claim respects eligibility). The eligibility check is
	 * authorization, not the race guard: only the conditional UPDATE prevents a
	 * double claim.
	 */
	public WorkItem take(String externalId, String actor) {
		return transactions.execute(status -> {
			WorkItem before = require(externalId);
			requireEligible(before, actor);
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

	// --- WP3: the timer — thresholds out, breaches in ------------------------

	/**
	 * {@inheritDoc}
	 *
	 * <p>The screen identity's {@code max_sec} wins; the
	 * {@code MAX_PROCESSING_TIME_SEC} global setting is the fallback; neither
	 * configured means no SLA. The {@code below_expected}/{@code expected}
	 * thresholds are deliberately not timers — they are grid display data, as in
	 * 1.x; only the breach is an engine fact (the narrow version, register #5).
	 */
	@Override
	public java.util.Optional<Duration> slaBreachAfter(String processDefinitionKey,
			String nodeReference) {
		java.util.Optional<Integer> screenMax = routing.screenFor(processDefinitionKey, nodeReference)
				.map(RoutingReadRepository.ScreenIdentity::maxSec);
		if (screenMax.isPresent()) {
			return screenMax.map(Duration::ofSeconds);
		}
		return routing.settingValue("MAX_PROCESSING_TIME_SEC")
				.map(String::trim)
				.filter(value -> value.chars().allMatch(Character::isDigit) && !value.isEmpty())
				.map(Long::parseLong)
				.filter(seconds -> seconds > 0)
				.map(Duration::ofSeconds);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Idempotent by predicate ({@code sla_breached_at IS NULL}), which is what
	 * "fires once across a restart" rests on: a timer job retried after a crash
	 * finds the record already written and writes nothing.
	 */
	@Override
	public void recordDueSlaBreaches(String processInstanceId) {
		transactions.executeWithoutResult(status -> {
			Instant now = Instant.now();
			for (WorkItem item : repository.openItemsOfProcessInstance(processInstanceId)) {
				if (item.slaBreachedAt() != null) {
					continue;
				}
				// Re-derive this item's own threshold: the timer that fired names
				// only the instance, and a sibling manual step not yet overdue must
				// not be marked. A small tolerance absorbs the skew between the
				// engine's clock arming the timer and the database's queued_at.
				java.util.Optional<Duration> threshold =
						slaBreachAfter(item.processDefinitionKey(), item.nodeReference());
				if (threshold.isEmpty()
						|| item.queuedAt().plus(threshold.get()).minusSeconds(5).isAfter(now)) {
					continue;
				}
				if (!repository.recordBreach(item.taskId(), now)) {
					log.debug("SLA breach for task {} was already recorded; not recording twice",
							item.taskId());
					continue;
				}
				repository.audit(item.workItemId(), siteExternalId, WorkItemAudit.SLA_BREACH,
						"system:sla-timer", item.assignee(), null, elapsedSince(item.queuedAt()));
				log.warn("work item {} breached its SLA (queued {}, threshold {}s, assignee {})",
						item.externalId(), item.queuedAt(), threshold.get().toSeconds(),
						item.assignee());
			}
		});
	}

	// --- eligibility (WP2) ---------------------------------------------------

	/**
	 * Who may claim: the pre-assigned operator; anyone, when the item has no
	 * screen or no routing rules (unrouted work claimable by all beats work
	 * nobody may touch); otherwise a member of an eligible team.
	 */
	private void requireEligible(WorkItem item, String actor) {
		if (actor.equals(item.assignee())) {
			return;
		}
		if (item.screenExternalId() == null) {
			return;
		}
		List<RoutingReadRepository.RouteRule> rules =
				routing.rulesFor(item.screenExternalId(), item.laneExternalId());
		if (rules.isEmpty()) {
			return;
		}
		List<String> eligibleTeams = rules.stream()
				.map(RoutingReadRepository.RouteRule::teamExternalId)
				.distinct().toList();
		if (!routing.isMemberOfAny(actor, eligibleTeams)) {
			throw new WorkItemIneligibleException(item.externalId(), actor, eligibleTeams);
		}
	}

	// --- reads --------------------------------------------------------------

	public WorkItem get(String externalId) {
		return require(externalId);
	}

	/**
	 * The grid read, with the sheet's ordering (§5): rules with a set priority
	 * before unset, lower number more urgent, oldest-queued as the tiebreak —
	 * and, deliberately, ONE ordering for every consumer: the Push selection in
	 * the presence work package reads the same sorted queue, closing 1.x's
	 * split-brain between the grid's SQL and the push path's map iteration.
	 *
	 * <p>Sorted here rather than in SQL: the priority lives on the routing rules
	 * (core's world), the item is runtime's, and the seam deliberately has no
	 * cross-schema join. The open queue is operationally bounded — trucks
	 * standing at a site's gates — so the fetch is capped, sorted, and trimmed.
	 */
	public List<WorkItem> list(String status, String laneExternalId, String assignee,
			String teamExternalId, int limit) {
		boolean openQueue = status == null || WorkItem.QUEUED.equals(status)
				|| WorkItem.IN_PROGRESS.equals(status);
		if (!openQueue) {
			return repository.list(status, laneExternalId, assignee, limit);
		}

		List<WorkItem> items = repository.list(status, laneExternalId, assignee,
				Math.max(limit, 2_000));

		List<RoutingReadRepository.TeamRule> rules = teamExternalId == null
				? routing.allRules()
				: routing.rulesOfTeam(teamExternalId);
		java.util.Map<String, Integer> priorityByScreenLane = new java.util.HashMap<>();
		java.util.Set<String> routedScreenLanes = new java.util.HashSet<>();
		for (RoutingReadRepository.TeamRule rule : rules) {
			String key = rule.screenExternalId() + "|" + rule.laneExternalId();
			routedScreenLanes.add(key);
			if (rule.priority() != null) {
				priorityByScreenLane.merge(key, rule.priority(), Math::min);
			}
		}
		// A team filter narrows to that team's work — plus the unrouted items,
		// which every grid shows because anyone may claim them.
		java.util.Set<String> anyRuleAtAll = teamExternalId == null ? routedScreenLanes
				: routing.allRules().stream()
						.map(rule -> rule.screenExternalId() + "|" + rule.laneExternalId())
						.collect(java.util.stream.Collectors.toSet());

		return items.stream()
				.filter(item -> {
					if (teamExternalId == null) {
						return true;
					}
					String key = item.screenExternalId() + "|" + item.laneExternalId();
					boolean unrouted = item.screenExternalId() == null || !anyRuleAtAll.contains(key);
					return unrouted || routedScreenLanes.contains(key);
				})
				.sorted(java.util.Comparator
						.comparing((WorkItem item) -> {
							Integer priority = priorityByScreenLane
									.get(item.screenExternalId() + "|" + item.laneExternalId());
							return priority == null ? 1 : 0;
						})
						.thenComparing(item -> {
							Integer priority = priorityByScreenLane
									.get(item.screenExternalId() + "|" + item.laneExternalId());
							return priority == null ? Integer.MAX_VALUE : priority;
						})
						.thenComparing(WorkItem::queuedAt))
				.limit(limit)
				.toList();
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
	 * The operator is outside the item's eligible teams (WP2). Distinguished from
	 * a conflict: nothing raced — this claim was never theirs to make.
	 */
	public static class WorkItemIneligibleException extends RuntimeException {

		public WorkItemIneligibleException(String externalId, String actor, List<String> eligibleTeams) {
			super("Operator '" + actor + "' is not a member of any team routed to work item '"
					+ externalId + "' (eligible teams: " + String.join(", ", eligibleTeams)
					+ "). The claim respects the routing rules.");
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
