package com.lynxis.orca.runtime.execution.delegate.support;

/**
 * The edge answered {@code UNKNOWN}: deadline passed or transport broke mid-flight — the
 * effect may have happened. A DISTINCT type from {@link EffectFailedException} on purpose:
 * operations must be able to tell "definitely did not fire" from "cannot know", and
 * retry policy differs (an UNKNOWN gate-arm raise is re-driven only under the idempotency
 * key that lets the edge deduplicate).
 */
public class EffectOutcomeUnknownException extends RuntimeException {

    public EffectOutcomeUnknownException(String message) {
        super(message);
    }
}
