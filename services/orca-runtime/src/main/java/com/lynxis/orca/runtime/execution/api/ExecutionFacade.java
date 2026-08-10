package com.lynxis.orca.runtime.execution.api;

import java.util.Map;

/**
 * The module's only public execution surface (Modulith-enforced once wiring lands):
 * start / signal / cancel in ORCA vocabulary — ORCA types only, no engine types.
 *
 * <p>W1a declares the seam; the implementation arrives with W4's vertical slice. Admission
 * (correlate-or-start, D6) deliberately sits in front of {@code start}: every inbound path —
 * camera, device host, partner API, portal — goes through the same single atomic
 * correlate-or-start.
 */
public interface ExecutionFacade {

    /**
     * Signal-payload key carrying a manual input's chosen outcome (the target node uuid of
     * the link the operator pressed). Promoted to the flat engine variable the compiled
     * outcome gateway routes on; every other payload entry is mirrored as dataset.
     */
    String OUTCOME_KEY = "outcome";

    /** Starts (or correlates into) an execution for a lane. Returns the execution uuid. */
    String startExecution(String workflowUuid, String siteUuid, Long laneId, Map<String, Object> dataset);

    /** Feeds a waiting node with a real-world event. */
    void signalExecution(String executionUuid, String nodeUuid, Map<String, Object> payload);

    /** Terminates an execution (lane reset path: fails open nodes and their work items together). */
    void cancelExecution(String executionUuid, String reason);

    /** The running visit on a lane, if any — admission's correlate-or-start key (D6). */
    java.util.Optional<String> runningExecutionOnLane(Long laneId);

    /** The node uuid a visit is parked at, if it is waiting. */
    java.util.Optional<String> waitingNodeOf(String executionUuid);
}
