package com.lynxis.orca.runtime.execution.delegate.spi;

/**
 * What the edge reports back. {@code UNKNOWN} is a first-class outcome: the deadline
 * passed or the transport broke mid-flight — the effect may or may not have happened.
 * Coercing it to FAILED double-fires gate arms; the delegates raise it as its own typed
 * failure so operations can tell the two apart.
 */
public enum EdgeOutcome {
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
