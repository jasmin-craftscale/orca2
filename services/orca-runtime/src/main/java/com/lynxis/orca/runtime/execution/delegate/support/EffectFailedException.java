package com.lynxis.orca.runtime.execution.delegate.support;

/** An effect the edge definitively reports FAILED — the step fails loud. */
public class EffectFailedException extends RuntimeException {

    public EffectFailedException(String message) {
        super(message);
    }
}
