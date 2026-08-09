package com.lynxis.orca.runtime.execution.domain;

import java.util.Set;

import com.lynxis.orca.platform.outbox.OutboxWriter;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.domain.ExecutionTables.Execution;

import lombok.extern.slf4j.Slf4j;

/**
 * Closes a visit when its process reaches an end state, and records the fact in the
 * same transaction.
 *
 * <p><strong>The two writes are one commit, and that is the whole point.</strong>
 * §D3 and the outbox primitive: <em>save the thing, then tell somebody</em> fails
 * in two directions and neither is detectable — the save commits and the
 * notification fails, so it happened and nobody was told; or the notification
 * succeeds and the transaction rolls back, so everybody was told about something
 * that did not happen. Under a network blip that is perhaps one in ten thousand,
 * which means it is never seen in testing and constantly seen in production.
 *
 * <p>This runs inside the engine's own command context — the same transaction that
 * is ending the process instance — so the visit row, the outbox row and the
 * engine's own state commit together or not at all. That is only possible because
 * the engine is on this database and this transaction manager (§C2, ADR-006).
 *
 * <h2>Which end state was reached</h2>
 *
 * <p>The end event's id decides it, and nothing is inferred from variables.
 * {@code visitReleased} completes the visit; anything else — today only
 * {@code manualHandlingRequired} — records {@link Execution#MANUAL}, which is a
 * <em>named</em> outcome rather than a failure: a visit that needed a human is
 * distinguishable from one that crashed.
 *
 * <p><strong>Only the released path writes a fact.</strong> The plan asks for
 * {@code visit.completed} on the happy path and names no other event, and inventing
 * {@code visit.manual_handling_required} would publish a contract nobody has agreed
 * and no consumer wants. A visit awaiting a human is visible as a row with status
 * {@code MANUAL}; the clerk workflow that reads it is Phase 2. Reported.
 */
@Slf4j
public class VisitCompletion {

	/** The end event that means the truck may go. Part of the BPMN execution profile. */
	public static final String RELEASED_END_EVENT = "visitReleased";

	/** §D3's ordering key shape: facts about one lane are delivered in sequence. */
	public static final String ORDERING_KEY_PREFIX = "lane:";

	public static final String VISIT_COMPLETED = "visit.completed";

	private final AdmissionRepository repository;
	private final OutboxWriter outbox;
	private final String siteExternalId;

	public VisitCompletion(AdmissionRepository repository, OutboxWriter outbox, String siteExternalId) {
		this.repository = repository;
		this.outbox = outbox;
		this.siteExternalId = siteExternalId;
	}

	/**
	 * @param endEventId the BPMN id of the end event the instance reached
	 */
	public void reachedEndState(String processInstanceId, String endEventId) {
		// The engine's worker has an identity but no scope — §B6 grants none
		// implicitly, and this is the deliberate act that gives it one.
		ScopeContext.runIn(Scope.of("site_external_id", Set.of(siteExternalId)),
				() -> close(processInstanceId, endEventId));
	}

	private void close(String processInstanceId, String endEventId) {
		AdmissionRepository.VisitRow visit = repository.visitByProcessInstance(processInstanceId)
				.orElse(null);
		if (visit == null) {
			// A process instance with no visit row. It cannot be one this service
			// admitted — admission writes both in one transaction — so it is a probe
			// process or a definition somebody deployed by hand.
			log.debug("process instance {} ended with no visit row; nothing to close", processInstanceId);
			return;
		}

		boolean released = RELEASED_END_EVENT.equals(endEventId);
		String status = released ? Execution.COMPLETED : Execution.MANUAL;

		int moved = repository.completeVisit(visit.executionId(), status);
		if (moved == 0) {
			// Already closed. A job that was retried after its transaction was rolled
			// back arrives here twice, and the second time there is nothing to do —
			// which is an ordinary outcome, not a failure. Crucially, the fact is NOT
			// written again: the guarded update is what makes this idempotent.
			log.debug("visit {} was already closed; not writing the fact twice", visit.externalId());
			return;
		}

		log.info("visit {} on lane {} reached '{}' -> {}", visit.externalId(), visit.laneId(),
				endEventId, status);

		if (!released) {
			return;
		}

		String laneExternalId = repository.laneExternalIdOf(visit.laneId()).orElse(String.valueOf(visit.laneId()));

		// In the caller's transaction. There is no flush, no send and no callback,
		// deliberately: anything a caller could do AFTER the commit is the failure
		// the outbox exists to remove.
		long publishSeq = outbox.write(ORDERING_KEY_PREFIX + laneExternalId, VISIT_COMPLETED,
				"""
				{"visitExternalId":"%s","laneExternalId":"%s","plate":%s}"""
						.formatted(visit.externalId(), laneExternalId, quoted(visit.plate())));

		log.info("recorded {} for visit {} at publish_seq {}", VISIT_COMPLETED, visit.externalId(),
				publishSeq);
	}

	private static String quoted(String value) {
		// Backslash before quote — the same fix as the work-item creation
		// listener's context writer, found by the same review pass.
		return value == null ? "null"
				: "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
