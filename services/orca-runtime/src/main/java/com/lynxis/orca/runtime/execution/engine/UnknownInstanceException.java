package com.lynxis.orca.runtime.execution.engine;

public final class UnknownInstanceException extends WorkflowEngineException {

    public UnknownInstanceException(EngineInstanceRef instance) {
        super("No instance '" + instance.value() + "'");
    }
}
