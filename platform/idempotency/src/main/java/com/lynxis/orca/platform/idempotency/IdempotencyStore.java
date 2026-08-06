package com.lynxis.orca.platform.idempotency;

/**
 * Records keys <em>and their outcomes</em>, so the same request applied twice has
 * the effect of once and the second caller gets the answer.
 *
 * <p>The obvious implementation — check whether it happened, then do it — fails in
 * two ways. Two concurrent requests both check, both find nothing, both proceed,
 * and the barrier rises twice. And the subtler one: a caller times out, retries,
 * and receives {@code 409 Duplicate} — but it retried <em>because it never saw the
 * first answer</em>, so an error is precisely what it cannot use. It retries
 * again, or reports a failure that in fact succeeded.
 *
 * <pre>{@code
 * switch (store.begin(commandId, "raise-gate", instanceId)) {
 *     case Fresh f      -> { var result = raiseGate(); store.complete(commandId, "raise-gate", result); }
 *     case Completed c  -> return c.outcome();     // the answer, not an error
 *     case InProgress p -> return stillWorking();  // NOT terminal — keep waiting
 * }
 * }</pre>
 */
public interface IdempotencyStore {

	/**
	 * Claims the key for this operation, or reports what is already known about it.
	 *
	 * <p>The claim is a single guarded insert, so of two concurrent first attempts
	 * exactly one gets {@link IdempotencyOutcome.Fresh} and the other gets
	 * {@link IdempotencyOutcome.InProgress}. There is no window between checking
	 * and claiming.
	 */
	IdempotencyOutcome begin(String key, String operation, String holderId);

	/** Records a successful outcome. Every later replay returns it verbatim. */
	void complete(String key, String operation, String outcome);

	/**
	 * Records a failed outcome.
	 *
	 * <p>A recorded failure is still a recorded outcome: replaying returns the same
	 * failure rather than re-running the operation. An operation that should be
	 * retried on failure must not be recorded as failed — it should be released.
	 */
	void fail(String key, String operation, String outcome);

	/**
	 * Abandons the claim so the operation can be attempted again.
	 *
	 * <p>For the case where the attempt did not reach the outside world at all. Once
	 * an actuating call has been made, {@code release} is the wrong tool — the
	 * physical outcome is unknown, and unknown is resolved by looking, not by
	 * retrying (§B10).
	 */
	void release(String key, String operation, String holderId);
}
