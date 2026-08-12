package com.lynxis.orca.runtime.execution.engine;

import java.util.Objects;

/** The engine's receipt for a deployment: which key, which version it became. */
public record EngineDeployment(String definitionKey, int version) {

    public EngineDeployment {
        Objects.requireNonNull(definitionKey, "definitionKey");
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1, got " + version);
        }
    }
}
