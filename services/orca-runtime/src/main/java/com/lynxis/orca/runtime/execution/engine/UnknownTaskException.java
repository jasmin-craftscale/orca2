package com.lynxis.orca.runtime.execution.engine;

/**
 * No such open task. A completed item's task is gone from the engine's runtime tables, so
 * this is also the answer to "act on an item somebody already finished" — the console's
 * stale-grid case, and a typed failure rather than a silent no-op (P2).
 */
public final class UnknownTaskException extends WorkflowEngineException {

    public UnknownTaskException(String taskId) {
        super("No open task '" + taskId + "'");
    }
}
