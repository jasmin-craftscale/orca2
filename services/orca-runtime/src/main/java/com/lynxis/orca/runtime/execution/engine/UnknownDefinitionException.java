package com.lynxis.orca.runtime.execution.engine;

public final class UnknownDefinitionException extends WorkflowEngineException {

    public UnknownDefinitionException(String definitionKey) {
        super("No deployed definition with key '" + definitionKey + "'");
    }
}
