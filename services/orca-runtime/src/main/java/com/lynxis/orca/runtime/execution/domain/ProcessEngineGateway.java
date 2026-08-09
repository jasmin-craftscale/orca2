package com.lynxis.orca.runtime.execution.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The platform's way in to the process engine.
 *
 * <p>The engine is reached behind this interface so the service is not written
 * against Flowable's API throughout. Its shape is the point: no Flowable type
 * appears in it, so the build rule
 * {@code EngineConfinementRule} can hold Flowable inside
 * {@code runtime.execution} and everything else in the service talks in
 * {@code String}s and {@code Map}s.
 *
 * <p>It is deliberately small. The engine is the reversible half of the workflow
 * design, and an interface that grew to mirror
 * {@code RuntimeService} would quietly remove that.
 */
public interface ProcessEngineGateway {

	/**
	 * Starts a visit's process instance.
	 *
	 * <p><strong>Called inside the caller's transaction</strong>, and that is the
	 * whole reason admission works: the visit row and the process instance commit
	 * together because the engine's tables are in the same schema and the same
	 * Spring transaction. A gateway that started the instance afterwards would
	 * reintroduce a window in which one exists without the other.
	 *
	 * @param processKey       the deployed process definition key, e.g. {@code gate-visit}
	 * @param businessKey      the visit's external identifier
	 * @param correlationKeys  process variables. Correlation keys and branch
	 *                         discriminators only — see {@link ProcessVariables}
	 * @return the engine's process instance identifier
	 */
	String startVisit(String processKey, String businessKey, Map<String, Object> correlationKeys);

	/** Whether that instance is still running. False once it has reached an end event. */
	boolean isRunning(String processInstanceId);

	/** Where the instance currently is, for diagnostics. Empty once it has finished. */
	Optional<String> currentActivity(String processInstanceId);

	/**
	 * Terminates a running instance — lane reset's engine half of aborting the visit
	 * and its related state in one transaction.
	 *
	 * <p><strong>Called inside the caller's transaction</strong>, like
	 * {@link #startVisit}: the instance's deletion, the visit's own closing write
	 * and the failing of its work items commit together or not at all. Terminating
	 * an instance that no longer exists is a no-op, not an error — a reset retried
	 * after a crash finds half the work already done.
	 *
	 * @param reason recorded in the engine's history, so an operator reading a
	 *               dead instance can see it was reset rather than crashed
	 */
	void terminate(String processInstanceId, String reason);
}
