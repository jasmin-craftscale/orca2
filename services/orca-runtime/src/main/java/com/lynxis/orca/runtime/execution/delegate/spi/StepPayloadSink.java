package com.lynxis.orca.runtime.execution.delegate.spi;

import java.util.Map;

/**
 * Where a step's payload is offered by a caller that holds ORCA identity rather than engine
 * identity — the queue finishing a work item knows its visit and its node, not the engine's
 * instance and activity ids.
 *
 * <p>The payload is offered <em>before</em> the transition that completes the step, because
 * the row it lands on does not exist until the engine reports the step complete.
 */
public interface StepPayloadSink {

    void offer(String executionUuid, String nodeUuid, Map<String, Object> payload);

    StepPayloadSink NO_OP = (executionUuid, nodeUuid, payload) -> {
    };
}
