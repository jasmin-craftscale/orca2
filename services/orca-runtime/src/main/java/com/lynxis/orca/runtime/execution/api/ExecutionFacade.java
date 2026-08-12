package com.lynxis.orca.runtime.execution.api;

import java.util.Map;

/**
 * The module's public execution surface: signal / cancel / inspect, in ORCA
 * vocabulary — ORCA types only, no engine types.
 *
 * <p>There is deliberately no {@code start} here. Admission owns
 * correlate-or-start — every inbound path (camera, device host, partner API,
 * portal) goes through the same single atomic correlate-or-start, and a start
 * method on this facade would be a second door around the lane lock and its
 * backstop. This facade picks up where admission leaves off.
 */
public interface ExecutionFacade {

    /**
     * Signal-payload key carrying a manual input's chosen outcome (the target node uuid of
     * the link the operator pressed). Promoted to the flat engine variable the compiled
     * outcome gateway routes on; every other payload entry is mirrored as dataset.
     */
    String OUTCOME_KEY = "outcome";

    /** Feeds a waiting node with a real-world event. */
    void signalExecution(String executionExternalId, String nodeUuid, Map<String, Object> payload);

    /** Terminates an execution's engine instance (the lane-reset path's engine half). */
    void cancelExecution(String executionExternalId, String reason);

    /** The running visit on a lane, if any — admission's correlate-or-start key. */
    java.util.Optional<String> runningExecutionOnLane(Long laneId);

    /** The node uuid a visit is parked at, if it is waiting. */
    java.util.Optional<String> waitingNodeOf(String executionExternalId);
}
