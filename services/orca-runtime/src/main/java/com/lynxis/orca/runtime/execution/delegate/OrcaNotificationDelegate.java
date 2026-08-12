package com.lynxis.orca.runtime.execution.delegate;

import com.lynxis.orca.runtime.execution.delegate.spi.NotificationSender;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

/** NOTIFICATION nodes (the compiled {@code ${orcaNotificationDelegate}}). */
public final class OrcaNotificationDelegate implements JavaDelegate {

    private final NotificationSender sender;

    public OrcaNotificationDelegate(NotificationSender sender) {
        this.sender = sender;
    }

    @Override
    public void execute(DelegateExecution execution) {
        sender.send(EdgeEffectDelegate.idempotencyKey(execution),
                execution.getTenantId(),
                execution.getCurrentActivityId(),
                execution.getCurrentFlowElement() == null ? null : execution.getCurrentFlowElement().getName());
    }
}
