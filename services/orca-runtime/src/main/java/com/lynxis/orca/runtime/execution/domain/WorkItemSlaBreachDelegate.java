package com.lynxis.orca.runtime.execution.domain;

import java.util.Set;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The breach path's one step — the compiled process's link target for "record
 * that this manual step passed its time target". The bean name is an API, like
 * the other two delegates.
 *
 * <p>It records and nothing else: no requeue, no reassignment, no notification.
 * The narrow version of escalation (register #5) is <em>detection + recording +
 * visibility</em>; a configurable escalation policy is a separate feature, not
 * implied here. The item stays exactly where it was — the timer is
 * non-interrupting, so the operator's claim and completion are untouched.
 *
 * <p>The work item is found by the process instance, and the workitem module
 * re-derives which open items are genuinely overdue — see
 * {@link WorkItemIntake#recordDueSlaBreaches} for why that is the precise shape.
 * A breach that finds nothing due records nothing; it does not throw, because a
 * retrying job that can never succeed becomes a dead letter about nothing.
 */
@Slf4j
@RequiredArgsConstructor
public class WorkItemSlaBreachDelegate implements JavaDelegate {

	private final WorkItemIntake workItems;
	private final String siteExternalId;

	@Override
	public void execute(DelegateExecution execution) {
		String processInstanceId = execution.getProcessInstanceId();
		ScopeContext.runIn(scope(), () -> workItems.recordDueSlaBreaches(processInstanceId));
	}

	private Scope scope() {
		return Scope.builder()
				.permit("site_external_id", Set.of(siteExternalId))
				.permit("config_realm", Set.of("INSTALLATION"))
				.build();
	}
}
