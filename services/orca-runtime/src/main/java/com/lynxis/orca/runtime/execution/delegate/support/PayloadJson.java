package com.lynxis.orca.runtime.execution.delegate.support;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * A step's payload as the selector evaluator expects to read it back: a JSON object, the same
 * shape the platform stores in {@code node_executions.execution_payload}. Keeping the shape
 * identical is what lets the ported evaluator resolve {@code $.<nodeUuid>.dataset.…} against
 * this runtime's rows without knowing which side wrote them.
 */
public final class PayloadJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadJson() {
    }

    /** Null for nothing worth recording — a step with no payload keeps a NULL column. */
    public static String of(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        return MAPPER.writeValueAsString(payload);
    }
}
