package com.lynxis.orca.runtime.execution.delegate.support;

import java.util.Map;
import org.flowable.engine.delegate.DelegateExecution;

/**
 * The ONLY class allowed to call {@code setVariable}:
 * process variables carry correlation and routing keys, never payloads — that discipline is
 * load-bearing for the {@code activity} history pin and tier-1 sizing. Everything written
 * here is either a routing key the compiled BPMN branches on, or a dataset mirror value a
 * compiled condition reads through its {@code orca:field} binding.
 */
public final class CorrelationKeys {

    /** The variable the compiled response gateways branch on. */
    public static final String RESPONSE_STATUS = "orcaResponseStatus";

    /** The variable the compiled manual-outcome gateways branch on (D-2026-08-07-1). */
    public static final String MANUAL_OUTCOME = "orcaManualOutcome";

    /**
     * The visit's lane, stamped at start. Not read by any compiled condition — it scopes a
     * mirror's recorded-answer lookups to the right lane (two lanes can run the same
     * workflow). Absent inside callActivity children.
     */
    public static final String LANE_ID = "orcaLaneId";

    private CorrelationKeys() {
    }

    public static void responseStatus(DelegateExecution execution, int status) {
        execution.setVariable(RESPONSE_STATUS, status);
    }

    /**
     * Mirrors extracted dataset entries under the names compiled conditions read (I8).
     * The dataset is VISIT-scoped (the Go semantics; PLT plan: subflow results "land on the
     * calling In-Gate execution") — but a callActivity child is its own variable scope, so
     * the mirror writes locally (child conditions read it) AND onto the root instance
     * (the parent reads it after the call returns). {@code inheritVariables} covers the
     * parent→child direction at start; this covers child→parent.
     */
    public static void datasetMirror(DelegateExecution execution,
            org.flowable.engine.RuntimeService runtime, Map<String, Object> dataset) {
        Map<String, Object> variables = DatasetVariables.mirror(dataset);
        variables.forEach(execution::setVariable);
        String root = execution.getRootProcessInstanceId();
        if (root != null && !root.equals(execution.getProcessInstanceId())) {
            variables.forEach((name, value) -> runtime.setVariable(root, name, value));
        }
    }
}
