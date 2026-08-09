package com.lynxis.orca.runtime.execution.api;

/**
 * The seam through which {@code workitem} completes a manual step and advances a
 * process parked inside {@code execution}.
 *
 * <p>{@code EngineConfinementRule} confines Flowable to {@code execution}, so
 * completing the engine task a work item parks on has to cross this
 * interface: no Flowable type appears in it, and the implementation lives with the
 * one module allowed to speak Flowable.
 *
 * <p><strong>Called inside the caller's transaction.</strong> The work item's
 * guarded status update and the engine's advance commit together or not at all —
 * inversion 1. An implementation that completed the task in its own transaction
 * would recreate 1.x's decoupled halves.
 */
public interface ManualStepPort {

	/**
	 * Completes the wait state the engine parked at, resuming the process.
	 *
	 * <p>The process continues <em>synchronously in this transaction</em> until its
	 * next wait state or async job — for the gate-visit shape that means the end
	 * event, so the visit's own closing write joins the same commit.
	 *
	 * @throws ProcessNotWaitingException when the engine holds no such open task —
	 *         the step already completed, the visit was reset, or the identifier
	 *         never named a task. The out-of-order submit, refused (inversion 3);
	 *         the caller's transaction rolls back with it
	 */
	void completeManualStep(String taskId) throws ProcessNotWaitingException;

	/** The engine is not waiting on that task. Typed, so the caller can answer 409 rather than 500. */
	class ProcessNotWaitingException extends RuntimeException {

		public ProcessNotWaitingException(String taskId, Throwable cause) {
			super("The engine holds no open task '" + taskId + "'. The step already completed, "
					+ "the visit was reset, or the submit was never for a step the process is "
					+ "waiting on. Refused rather than applied out of order.", cause);
		}
	}
}
