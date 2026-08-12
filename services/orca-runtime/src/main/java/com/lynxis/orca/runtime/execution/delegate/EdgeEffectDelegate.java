package com.lynxis.orca.runtime.execution.delegate;

import com.lynxis.orca.runtime.execution.delegate.spi.EdgeClient;
import com.lynxis.orca.runtime.execution.delegate.spi.EdgeCommand;
import com.lynxis.orca.runtime.execution.delegate.spi.EdgeOutcome;
import com.lynxis.orca.runtime.execution.delegate.support.EffectFailedException;
import com.lynxis.orca.runtime.execution.delegate.support.EffectOutcomeUnknownException;
import java.time.Duration;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

/**
 * The shared shape of every effect that crosses the edge (device commands and displays):
 * one {@link EdgeClient} call under a deterministic idempotency key and a deadline, with
 * {@code UNKNOWN} raised as its own typed failure.
 *
 * <p>There is deliberately no suppression flag here. The system this was translated from
 * ran in parallel with its predecessor and needed every effect gated off; this repository
 * has no shadow mode, and its protection against a misconfigured runtime is the same one
 * the rest of the module uses — the default {@link EdgeClient} refuses loudly, so a
 * runtime booted without the adapter fails the step by name rather than quietly acting.
 */
abstract class EdgeEffectDelegate implements JavaDelegate {

    /** Edge answers within this or answers UNKNOWN. Configurable when the adapter lands. */
    static final Duration EDGE_DEADLINE = Duration.ofSeconds(30);

    private final EdgeClient edge;
    private final EdgeCommand.EdgeKind kind;

    EdgeEffectDelegate(EdgeClient edge, EdgeCommand.EdgeKind kind) {
        this.edge = edge;
        this.kind = kind;
    }

    @Override
    public final void execute(DelegateExecution execution) {
        EdgeCommand command = new EdgeCommand(
                idempotencyKey(execution),
                execution.getTenantId(),
                kind,
                execution.getCurrentActivityId(),
                execution.getCurrentFlowElement() == null ? null : execution.getCurrentFlowElement().getName(),
                orcaAttribute(execution, "topic"),
                EDGE_DEADLINE);
        EdgeOutcome outcome = edge.perform(command);
        switch (outcome) {
            case SUCCEEDED -> {
                // the engine advances; the recorder lands the step in the same transaction
            }
            case FAILED -> throw new EffectFailedException(
                    kind + " '" + command.name() + "' (" + command.nodeUuid() + ") FAILED at the edge");
            case UNKNOWN -> throw new EffectOutcomeUnknownException(
                    kind + " '" + command.name() + "' (" + command.nodeUuid()
                            + ") outcome UNKNOWN — deadline " + EDGE_DEADLINE
                            + " passed; re-drive only under idempotency key " + command.idempotencyKey());
        }
    }

    /** Deterministic per step attempt-set: retries reuse it, the edge deduplicates. */
    static String idempotencyKey(DelegateExecution execution) {
        return execution.getProcessInstanceId() + ":" + execution.getCurrentActivityId();
    }

    /** A compiled {@code orca:*} attribute off the current element, or null. */
    static String orcaAttribute(DelegateExecution execution, String name) {
        if (!(execution.getCurrentFlowElement() instanceof BaseElement element)) {
            return null;
        }
        var values = element.getAttributes().get(name);
        if (values == null) {
            return null;
        }
        for (ExtensionAttribute attribute : values) {
            if (attribute.getValue() != null) {
                return attribute.getValue();
            }
        }
        return null;
    }
}
