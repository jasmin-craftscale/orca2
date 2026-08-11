package com.lynxis.orca.runtime.execution.internal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.api.ExecutionFacade;
import com.lynxis.orca.runtime.execution.api.SignalPayload;
import com.lynxis.orca.runtime.execution.engine.EngineInstanceRef;
import com.lynxis.orca.runtime.execution.engine.InvalidSignalException;
import com.lynxis.orca.runtime.execution.engine.UnknownInstanceException;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngine;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;

/**
 * {@link ExecutionFacade} on the engine seam — ORCA vocabulary in, ORCA
 * vocabulary out, engine-free by construction (it speaks {@link WorkflowEngine}),
 * which keeps the engine choice reversible across the whole call path.
 *
 * <p>What is deliberately NOT here: starting a visit. Admission owns
 * correlate-or-start — the lane lock, the filtered-index backstop and the
 * engine start in one transaction — and a second start door on this facade
 * would be a path around the one property the whole design turns on. This
 * facade begins where admission ends: a visit that exists, resumed, inspected
 * or cancelled.
 *
 * <p>Every dataset value entering through a signal is written durably BEFORE
 * the engine advances, in the same transaction: a visit must never be past the
 * step whose data is missing.
 */
public final class RuntimeExecutionFacade implements ExecutionFacade {

	private final WorkflowEngine engine;
	private final AdmissionRepository visits;
	private final VisitDataWriter visitData;
	private final Scope installationScope;

	public RuntimeExecutionFacade(WorkflowEngine engine, AdmissionRepository visits,
			VisitDataWriter visitData, String siteExternalId) {
		this.engine = engine;
		this.visits = visits;
		this.visitData = visitData;
		this.installationScope = Scope.of("site_external_id", Set.of(siteExternalId));
	}

	@Override
	public void signalExecution(String executionExternalId, String nodeUuid, Map<String, Object> payload) {
		AdmissionRepository.VisitRow visit = requireVisit(executionExternalId);
		if (visit.processInstanceId() == null) {
			throw new InvalidSignalException("visit '" + executionExternalId
					+ "' has no engine correlation — nothing is waiting to be signalled");
		}
		// Durable first, then the advance: both are in this transaction, so a
		// failure takes the pair down together.
		visitData.recordByExecutionUuid(executionExternalId, SignalPayload.datasetOf(payload));
		// What this step saw, for the recorder to put on its row when the engine
		// fires the step's completion inside the signal below.
		visitData.offer(executionExternalId, nodeUuid, SignalPayload.datasetOf(payload));
		engine.signal(new EngineInstanceRef(visit.processInstanceId()), "n_" + nodeUuid,
				SignalPayload.toEngineVariables(payload));
	}

	@Override
	public void cancelExecution(String executionExternalId, String reason) {
		AdmissionRepository.VisitRow visit = requireVisit(executionExternalId);
		if (visit.processInstanceId() == null) {
			throw new InvalidSignalException("visit '" + executionExternalId
					+ "' has no engine correlation — a lane reset, not an engine cancel, "
					+ "is how a visit that never reached the engine ends");
		}
		engine.cancel(new EngineInstanceRef(visit.processInstanceId()), reason);
		// The recorder closes CHILD rows off the engine's own event, in the same
		// transaction; the root's row belongs to the visit-completion path.
	}

	@Override
	public Optional<String> runningExecutionOnLane(Long laneId) {
		if (laneId == null) {
			return Optional.empty();
		}
		return scoped(() -> visits.activeRootOn(laneId))
				.map(AdmissionRepository.ActiveVisit::externalId);
	}

	@Override
	public Optional<String> waitingNodeOf(String executionExternalId) {
		AdmissionRepository.VisitRow visit = requireVisit(executionExternalId);
		if (visit.processInstanceId() == null) {
			return Optional.empty();
		}
		return engine.stateOf(new EngineInstanceRef(visit.processInstanceId()))
				.waitPoint()
				.map(waitPoint -> waitPoint.startsWith("n_") ? waitPoint.substring(2) : waitPoint);
	}

	private AdmissionRepository.VisitRow requireVisit(String executionExternalId) {
		return scoped(() -> visits.visitByExternalId(executionExternalId))
				.orElseThrow(() -> new UnknownInstanceException(
						new EngineInstanceRef(executionExternalId)));
	}

	private <T> T scoped(java.util.concurrent.Callable<T> work) {
		return ScopeContext.callIn(installationScope, work);
	}
}
