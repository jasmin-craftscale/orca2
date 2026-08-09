package com.lynxis.orca.runtime.execution.domain;

import java.util.Optional;

import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.runtime.execution.domain.ExecutionTables.Execution;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;

import lombok.extern.slf4j.Slf4j;

/**
 * §C2's lane reset: <em>"abort the visit, fail its open nodes and its work items
 * together, set the lane status"</em> — <strong>one transaction</strong>, and the
 * writer that makes {@code work_item.FAILED} a status with a writer rather than a
 * takeable state nothing produces (sheet §2).
 *
 * <p>It takes the <strong>lane lock first</strong>, like every admission does:
 * a reset racing an arriving truck must serialise on the same row, or the reset
 * could abort a visit while admission is mid-way through attaching an event to it.
 * Coarse resource before fine, on every path — the WP6 ordering rule.
 *
 * <p>What it deliberately does not do: touch the lane's out-of-service flag (that
 * is core's configuration, not runtime's state — §C2's "set the lane status" reads
 * on the lane <em>session</em>, whose bound plate this clears), and fail node
 * executions (the {@code node_execution} table is not built in any phase yet —
 * recorded, not guessed at).
 */
@Slf4j
public class LaneResetService {

	private final AdmissionRepository repository;
	private final ProcessEngineGateway engine;
	private final WorkItemIntake workItems;
	private final TransactionTemplate transactions;

	public LaneResetService(AdmissionRepository repository, ProcessEngineGateway engine,
			WorkItemIntake workItems, TransactionTemplate transactions) {
		this.repository = repository;
		this.engine = engine;
		this.workItems = workItems;
		this.transactions = transactions;
	}

	/**
	 * @return what was reset — or an empty result when the lane was already clear,
	 *         which is an ordinary outcome for a retried reset, not a failure
	 */
	public LaneReset reset(String laneExternalId, String actor) {
		long laneId = repository.laneIdOf(laneExternalId)
				.orElseThrow(() -> new AdmissionService.LaneNotAtThisInstallationException(laneExternalId));

		return transactions.execute(status -> {
			if (!repository.lockLane(laneId)) {
				// No session row: no truck was ever admitted here. Nothing to reset.
				return new LaneReset(laneExternalId, null, 0);
			}

			Optional<AdmissionRepository.ActiveVisit> active = repository.activeRootOn(laneId);
			if (active.isEmpty()) {
				return new LaneReset(laneExternalId, null, 0);
			}

			AdmissionRepository.VisitRow visit = repository.visitByExternalId(active.get().externalId())
					.orElseThrow(() -> new IllegalStateException("active visit "
							+ active.get().externalId() + " has no row — the correlate read and the "
							+ "row read disagree inside one transaction, which should be impossible"));

			// All three writers in ONE transaction — the §C2 sentence, executed:
			// the items fail, the engine instance dies, the visit closes FAILED.
			int failedItems = workItems.failOpenItemsFor(visit.executionId(), actor);
			if (visit.processInstanceId() != null) {
				engine.terminate(visit.processInstanceId(), "lane reset by " + actor);
			}
			repository.completeVisit(visit.executionId(), Execution.FAILED);
			repository.bindLane(laneId, null, false);

			log.info("lane {} reset by {}: visit {} failed, {} open work item(s) failed with it",
					laneExternalId, actor, visit.externalId(), failedItems);
			return new LaneReset(laneExternalId, visit.externalId(), failedItems);
		});
	}

	/** @param visitExternalId the visit that was aborted, or null when the lane was already clear */
	public record LaneReset(String laneExternalId, String visitExternalId, int failedWorkItems) {
	}
}
