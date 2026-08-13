package com.lynxis.orca.runtime.execution.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The clerk-work half of the reversibility seam: open work, who holds it, and the four
 * transitions a console can drive. Split from {@link WorkflowEngine} rather than bolted onto
 * it because they are different questions — one is about a visit, the other about a queue —
 * and a caller that only serves grids has no business holding a handle that can cancel a
 * truck. Both engines implement both interfaces; the state is shared, the surfaces are not.
 *
 * <p><b>No Flowable type may appear in any signature</b> — ArchUnit rule 6, same as the
 * instance seam. What this buys beyond reversibility is the point:
 * the ORCA lifecycle stops being a hand-rolled conditional UPDATE and becomes the engine's
 * own assignment, whose only-one-winner property is a property of the row's version, not of
 * a WHERE clause somebody has to keep correct.
 *
 * <p>The contract is specified by {@code ClerkTaskEngineContract}: one contract test, two
 * implementations.
 */
public interface ClerkTaskEngine {

    /** Every open clerk task of a visit, in creation order. Empty when the visit waits elsewhere. */
    List<ClerkTask> openTasks(EngineInstanceRef instance);

    /** The open task, or empty when it never existed or has already been completed. */
    Optional<ClerkTask> findTask(String taskId);

    /**
     * Takes an unheld item for {@code assignee} — the console's "take".
     *
     * @throws TaskAlreadyClaimedException when somebody else already holds it, whether the
     *     loser saw the winner's assignee or lost the row-version race
     * @throws UnknownTaskException when there is no such open task
     */
    void claim(String taskId, String assignee);

    /**
     * Puts a held item back on the queue — "park". Unassigned is queued, so this is the whole
     * transition on the engine side. Parking an already-queued item is a no-op, not a failure:
     * the console's park button is idempotent by design.
     *
     * @throws UnknownTaskException when there is no such open task
     */
    void release(String taskId);

    /**
     * Moves an item to {@code assignee} regardless of who holds it — "takeover" and
     * supervisor "assign", which are deliberately unconditional. The audit row that records
     * who was displaced is ORCA's, written by the workitem module in the same transaction.
     *
     * @throws UnknownTaskException when there is no such open task
     */
    void reassign(String taskId, String assignee);

    /**
     * Finishes the item and advances the visit <em>in one transaction</em> — the whole point
     * of D4.3. There is no window in which the console believes the item done and the engine
     * still waits at it.
     *
     * @throws UnknownTaskException when there is no such open task
     */
    void complete(String taskId, Map<String, Object> payload);
}
