package com.lynxis.orca.runtime.execution.engine;

/**
 * The loser's answer in a claim race — one typed failure covering both ways a claim can lose.
 *
 * <p>Two clerks pressing "take" on the same item at the same instant lose differently
 * depending on timing: the second may read the assignee the first wrote (a plain conflict),
 * or both may read it unassigned and one's write is rejected by the row's optimistic version.
 * Callers cannot act on the difference and must not have to — {@code take} either won or it
 * did not, and the console shows the same message either way.
 */
public final class TaskAlreadyClaimedException extends WorkflowEngineException {

    private final String taskId;

    public TaskAlreadyClaimedException(String taskId, String heldBy) {
        super("Task '" + taskId + "' is already claimed"
                + (heldBy == null || heldBy.isBlank() ? "" : " by '" + heldBy + "'"));
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }
}
