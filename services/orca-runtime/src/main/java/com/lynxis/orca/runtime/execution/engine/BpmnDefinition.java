package com.lynxis.orca.runtime.execution.engine;

import java.util.Arrays;
import java.util.Objects;

/**
 * A compiled BPMN 2.0 definition, ready for a BPMN engine to deploy. Engine-agnostic on
 * purpose: bytes and a key, no engine type. The compiler produces these; the
 * Flowable adapter deploys them.
 *
 * <p>The XML's {@code <process id>} must equal {@link #definitionKey()} — the adapter
 * fails the deployment loudly when they diverge (P2: a key/id mismatch silently deploys a
 * definition nobody can start).
 */
public record BpmnDefinition(String definitionKey, byte[] bpmnXml) implements DeployableDefinition {

    public BpmnDefinition {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(bpmnXml, "bpmnXml");
        if (definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (bpmnXml.length == 0) {
            throw new IllegalArgumentException("bpmnXml must not be empty");
        }
        bpmnXml = bpmnXml.clone();
    }

    @Override
    public byte[] bpmnXml() {
        return bpmnXml.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BpmnDefinition that
                && definitionKey.equals(that.definitionKey)
                && Arrays.equals(bpmnXml, that.bpmnXml);
    }

    @Override
    public int hashCode() {
        return 31 * definitionKey.hashCode() + Arrays.hashCode(bpmnXml);
    }

    @Override
    public String toString() {
        return "BpmnDefinition[" + definitionKey + ", " + bpmnXml.length + " bytes]";
    }
}
