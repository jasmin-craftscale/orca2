package com.lynxis.orca.runtime.execution.api.dto;

import java.util.Objects;

/**
 * A published, compiled workflow: the executable BPMN alongside the identity the engine and
 * the goldens key on.
 *
 * @param workflowId    ORCA {@code workflow_id} — the only sanctioned identity (never a name)
 * @param processKey    {@code proc_<workflowId>} — short by construction, so the engine-side
 *                      definition id {@code key:version:uuid} fits varchar(64)
 * @param bpmnXml       canonical serialization — byte-identity is the T2 gate
 */
public record CompiledDefinition(long workflowId, String processKey, byte[] bpmnXml) {

    public CompiledDefinition {
        Objects.requireNonNull(processKey, "processKey");
        Objects.requireNonNull(bpmnXml, "bpmnXml");
        bpmnXml = bpmnXml.clone();
    }

    @Override
    public byte[] bpmnXml() {
        return bpmnXml.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CompiledDefinition other
                && workflowId == other.workflowId
                && processKey.equals(other.processKey)
                && java.util.Arrays.equals(bpmnXml, other.bpmnXml);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workflowId, processKey, java.util.Arrays.hashCode(bpmnXml));
    }

    @Override
    public String toString() {
        return "CompiledDefinition[workflowId=" + workflowId + ", processKey=" + processKey
                + ", bpmnXml=" + bpmnXml.length + " bytes]";
    }
}
