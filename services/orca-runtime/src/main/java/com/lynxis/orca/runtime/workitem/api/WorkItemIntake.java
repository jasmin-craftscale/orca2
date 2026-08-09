package com.lynxis.orca.runtime.workitem.api;

/**
 * What the {@code execution} module tells {@code workitem}, and nothing more.
 *
 * <p>The module wall (§C2, {@code ModuleWallRule}) closes another module's
 * {@code domain} and {@code persistence} packages; the {@code api} package is the
 * seam — the same shape as {@code integration.api.ConnectorPort}, which is how
 * {@code execution} calls a connector without touching the connector's tables.
 *
 * <p>The direction matters. §C2's component diagram: <em>execution "raises a work
 * item" → workitem</em>, and <em>workitem "complete → advance" → execution</em>.
 * This interface is the first arrow; {@code execution.api.ManualStepPort} is the
 * second. Neither module sees the other's internals.
 */
public interface WorkItemIntake {

	/**
	 * A process reached a manual-input wait state and the engine has parked.
	 *
	 * <p><strong>Called inside the engine's own transaction</strong> — the one that
	 * is entering the wait state — so the work item and the parked task commit
	 * together or not at all (inversion 1 of the sheet's §0). An implementation
	 * that deferred this to after the commit would reintroduce 1.x's two
	 * independent one-way calls, which is the exact shape this phase exists to
	 * invert.
	 *
	 * @param step everything the item records about where it came from, resolved
	 *             by the execution module from its own state — workitem reads no
	 *             other module's tables
	 */
	void manualStepReached(ManualStep step);

	/**
	 * Lane reset: fail every open item of this visit, in the caller's transaction
	 * (§C2 — the reset fails the visit and its work items <em>together</em>).
	 *
	 * @return how many items moved to {@code FAILED}
	 */
	int failOpenItemsFor(long executionId, String actor);

	/**
	 * One parked manual step, as the execution module saw it when the engine
	 * stopped there.
	 *
	 * @param taskId               the engine's handle for the wait state — the
	 *                             thing completion will present back
	 * @param nodeReference        the BPMN task definition key — the screen
	 *                             identity's node reference points at this
	 * @param eventData            the captured context of the step, as JSON built
	 *                             from the process's branch discriminators —
	 *                             what the operator will see went wrong
	 */
	record ManualStep(
			String taskId,
			String processInstanceId,
			String processDefinitionKey,
			String nodeReference,
			long executionId,
			long laneId,
			String laneExternalId,
			String visitExternalId,
			String eventData) {
	}
}
