package com.lynxis.orca.runtime.execution.engine;

/** Base of the seam's typed failures — engine-agnostic, like everything else here. */
public class WorkflowEngineException extends RuntimeException {

    public WorkflowEngineException(String message) {
        super(message);
    }
}
