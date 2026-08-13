package com.lynxis.orca.runtime.execution.engine;

import java.util.Objects;

/**
 * Opaque handle to a running instance. Engine-side identity only — business data lives in
 * platform tables keyed by the execution identifier, never in engine state.
 */
public record EngineInstanceRef(String value) {

    public EngineInstanceRef {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("instance ref must not be blank");
        }
    }
}
