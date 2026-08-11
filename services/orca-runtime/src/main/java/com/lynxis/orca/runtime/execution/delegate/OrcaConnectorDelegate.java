package com.lynxis.orca.runtime.execution.delegate;

import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorGateway;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorRequest;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorResult;
import com.lynxis.orca.runtime.execution.delegate.spi.VisitDataSink;
import com.lynxis.orca.runtime.execution.delegate.spi.VisitIdentity;
import com.lynxis.orca.runtime.execution.delegate.support.CorrelationKeys;
import com.lynxis.orca.runtime.execution.delegate.support.PayloadJson;
import com.lynxis.orca.runtime.execution.engine.flowable.StepPayloads;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

/**
 * CONNECTOR nodes (the compiled {@code ${orcaConnectorDelegate}}): one call through the
 * integration seam, then two writes the compiled BPMN depends on — the status code the
 * response gateway routes on, and the extracted dataset entries downstream conditions read.
 *
 * <p>A connector cannot no-op: routing needs its response. A runtime with no
 * {@link ConnectorGateway} adapter configured therefore fails the step by name — never a
 * fabricated 200, which would route the visit down a branch its author wrote for a real
 * answer.
 */
public final class OrcaConnectorDelegate implements JavaDelegate {

    private final ConnectorGateway gateway;
    private final RuntimeService runtime;
    private final VisitDataSink visitData;
    private final VisitIdentity visits;

    public OrcaConnectorDelegate(ConnectorGateway gateway, RuntimeService runtime,
            VisitDataSink visitData, VisitIdentity visits) {
        this.gateway = gateway;
        this.runtime = runtime;
        this.visitData = visitData;
        this.visits = visits;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String name = execution.getCurrentFlowElement() == null
                ? null : execution.getCurrentFlowElement().getName();
        // The request carries the ORCA node uuid, not the BPMN activity id — gateways
        // resolve against ORCA data, which has no "n_" prefix.
        String activityId = execution.getCurrentActivityId();
        String nodeUuid = activityId != null && activityId.startsWith("n_")
                ? activityId.substring(2) : activityId;
        Object laneId = execution.getVariable(CorrelationKeys.LANE_ID);
        // The VISIT, not this execution: a subflow's connector resolves its selectors against
        // the calling visit, which is where the dataset and the node payloads live.
        String root = execution.getRootProcessInstanceId() == null
                ? execution.getProcessInstanceId() : execution.getRootProcessInstanceId();
        ConnectorResult result = gateway.call(new ConnectorRequest(
                EdgeEffectDelegate.idempotencyKey(execution),
                nodeUuid,
                name,
                execution.getTenantId(),
                laneId instanceof Number n ? n.longValue() : null,
                visits.forInstance(root).orElse(null)));
        CorrelationKeys.responseStatus(execution, result.status());
        CorrelationKeys.datasetMirror(execution, runtime, result.dataset());
        // The mirror is what conditions read; this is what the NEXT connector sends. Visit-
        // scoped: a subflow's extracted fields land on the calling visit, as they do today.
        visitData.record(root, result.dataset());
        // And onto this step's own row, so `$.<nodeUuid>.dataset.…` can read back exactly
        // what this connector answered.
        StepPayloads.offer(execution.getProcessInstanceId(), activityId,
                PayloadJson.of(result.dataset()));
    }
}
