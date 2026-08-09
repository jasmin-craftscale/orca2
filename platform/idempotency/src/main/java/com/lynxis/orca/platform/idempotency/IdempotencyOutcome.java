package com.lynxis.orca.platform.idempotency;

/**
 * What {@link IdempotencyStore#begin} found, and what the caller should do about it.
 *
 * <p>A sealed hierarchy rather than a status enum, because the three cases carry
 * different data and the compiler should say so. In particular
 * {@link Completed} carries the recorded outcome — the whole reason this
 * primitive exists — and {@link InProgress} carries none, because there is not one
 * yet.
 */
public sealed interface IdempotencyOutcome {

	/**
	 * Nobody has used this key for this operation. The caller executes, then calls
	 * {@code complete} or {@code fail}.
	 */
	record Fresh(String key, String operation) implements IdempotencyOutcome {
	}

	/**
	 * Someone else is executing it right now.
	 *
	 * <p><strong>This is not a terminal state and it is not an answer.</strong> The
	 * caller keeps waiting for the real outcome. Treating it as a failure would
	 * misreport a slow device host: it may have raised the barrier before answering,
	 * yet the gate would record that the physical action did not happen.
	 */
	record InProgress(String key, String operation, String holderId) implements IdempotencyOutcome {
	}

	/**
	 * It already ran, and here is what it produced.
	 *
	 * <p>Not "duplicate". The caller retried <em>because it never saw the first
	 * answer</em>, so an error is the one response that cannot help it. This is the
	 * distinction that makes a retry safe rather than merely rejected.
	 *
	 * @param successful whether the recorded run succeeded — a recorded failure is
	 *                   still a recorded outcome, and replaying it must return the
	 *                   same failure rather than re-running the operation
	 */
	record Completed(String key, String operation, String outcome, boolean successful)
			implements IdempotencyOutcome {
	}
}
