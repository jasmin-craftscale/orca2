package com.lynxis.orca.runtime.execution.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The platform's way in to the process engine.
 *
 * <p>§C2: the engine <em>"is reached behind an interface so the platform is not
 * written against a specific engine's API throughout"</em>. This is that interface,
 * and its shape is the point: no Flowable type appears in it, so the ArchUnit rule
 * {@code EngineConfinementRule} can hold Flowable inside
 * {@code runtime.execution} and everything else in the service talks in
 * {@code String}s and {@code Map}s.
 *
 * <p>It is deliberately small. ADR-006 records that the engine is the half of the
 * decision that <em>can</em> be unwound, and an interface that grew to mirror
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
	 * reintroduce the window WP0 exists to close.
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
}
