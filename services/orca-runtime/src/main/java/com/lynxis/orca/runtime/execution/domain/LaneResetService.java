package com.lynxis.orca.runtime.execution.domain;

import java.util.Optional;

import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.runtime.execution.domain.ExecutionTables.Execution;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;

import lombok.extern.slf4j.Slf4j;

/**
 * Lane reset aborts the visit, fails its open nodes and work items together, and
 * clears the lane session in <strong>one transaction</strong>. This is also the
 * writer that makes {@code work_item.FAILED} a real status rather than a state
 * nothing produces.
 *
 * <p>It takes the <strong>lane lock first</strong>, like every admission does:
 * a reset racing an arriving truck must serialise on the same row, or the reset
 * could abort a visit while admission is mid-way through attaching an event to it.
 * Coarse resource before fine, in the same order on every path.
 *
 * <p>What it deliberately does not do: touch the lane's out-of-service flag (that
 * is core's configuration, not runtime's state; the mutable lane state here is the
 * lane <em>session</em>, whose bound plate this clears), or fail node executions.
 * No {@code node_execution} table exists yet, so that behavior remains absent
 * rather than guessed at.
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

			// All three writers in ONE transaction:
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
