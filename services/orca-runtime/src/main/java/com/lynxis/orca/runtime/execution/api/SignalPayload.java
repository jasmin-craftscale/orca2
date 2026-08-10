package com.lynxis.orca.runtime.execution.api;

import com.lynxis.orca.runtime.execution.delegate.support.CorrelationKeys;
import com.lynxis.orca.runtime.execution.delegate.support.DatasetVariables;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one translation from an ORCA payload to the engine variables a compiled definition
 * reads: {@code outcome} becomes the routing key the manual-outcome gateway branches on
 * (D-2026-08-07-1), and every other entry is mirrored under the {@code v_*} name the
 * {@code orca:field} sidecar bound (I8).
 *
 * <p>It lives on the public surface because more than one caller resumes a visit — the
 * mirror feeds device events through {@link ExecutionFacade}, a clerk finishes a work item
 * through the queue — and a second copy of this mapping is a second chance to get the
 * variable names wrong, which fails silently: an unmirrored dataset value reads null and the
 * condition quietly takes the other branch.
 */
public final class SignalPayload {

    private SignalPayload() {
    }

    /**
     * The payload's dataset half — everything except the routing outcome. This is what
     * becomes durable ({@code runtime.visit_dataset}); the outcome is a routing key and
     * belongs to the token, not to the truck.
     */
    public static Map<String, Object> datasetOf(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> dataset = new LinkedHashMap<>(payload);
        dataset.remove(ExecutionFacade.OUTCOME_KEY);
        return dataset;
    }

    /** ORCA payload in, engine variables out. A null or empty payload contributes nothing. */
    public static Map<String, Object> toEngineVariables(Map<String, Object> payload) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            return variables;
        }
        Map<String, Object> dataset = new LinkedHashMap<>(payload);
        Object outcome = dataset.remove(ExecutionFacade.OUTCOME_KEY);
        variables.putAll(DatasetVariables.mirror(dataset));
        if (outcome != null) {
            variables.put(CorrelationKeys.MANUAL_OUTCOME, outcome);
        }
        return variables;
    }
}
