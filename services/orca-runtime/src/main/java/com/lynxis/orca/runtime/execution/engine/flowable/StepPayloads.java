package com.lynxis.orca.runtime.execution.engine.flowable;

import java.util.HashMap;
import java.util.Map;

/**
 * A one-command lookaside between the thing that KNOWS a step's payload and the thing that
 * WRITES the step's row.
 *
 * <p>The two are necessarily different objects and necessarily in that order: a delegate has
 * the connector's answer while the service task is still running, and the {@code
 * node_execution} row for that task does not exist until the engine fires ACTIVITY_COMPLETED
 * afterwards. Rather than give the payload a second writer — which is how a row ends up
 * written twice, or once with the wrong content — the producer leaves it here and
 * {@link NodeExecutionRecorder} drains it when it creates the row.
 *
 * <p>Thread-scoped, and that is exactly right: a Flowable command runs start to finish on one
 * thread, so the map's lifetime is the command's. {@link #clear()} on the way out keeps a
 * pooled thread from carrying one visit's payload into the next.
 */
public final class StepPayloads {

    private static final ThreadLocal<Map<String, String>> PENDING = new ThreadLocal<>();

    private StepPayloads() {
    }

    /** Offers the payload of {@code activityId} in {@code processInstanceId}. */
    public static void offer(String processInstanceId, String activityId, String payloadJson) {
        if (processInstanceId == null || activityId == null || payloadJson == null) {
            return;
        }
        Map<String, String> pending = PENDING.get();
        if (pending == null) {
            pending = new HashMap<>();
            PENDING.set(pending);
        }
        pending.put(key(processInstanceId, activityId), payloadJson);
    }

    /** Takes the payload, if one was offered. Removing is deliberate: it is consumed once. */
    static String take(String processInstanceId, String activityId) {
        Map<String, String> pending = PENDING.get();
        if (pending == null || processInstanceId == null || activityId == null) {
            return null;
        }
        String payload = pending.remove(key(processInstanceId, activityId));
        if (pending.isEmpty()) {
            PENDING.remove();
        }
        return payload;
    }

    /** Drops anything a command left behind — a step that failed before its row was written. */
    public static void clear() {
        PENDING.remove();
    }

    private static String key(String processInstanceId, String activityId) {
        return processInstanceId + '|' + activityId;
    }
}
