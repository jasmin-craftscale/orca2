package com.lynxis.orca.runtime.execution.domain;

import java.time.Duration;
import java.util.Set;

import org.flowable.engine.delegate.DelegateExecution;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;

import lombok.extern.slf4j.Slf4j;

/**
 * The SLA boundary timer's duration source — the bean the compiled process's
 * timer expression calls when the engine <em>arms</em> the timer, which is the
 * moment the manual-input wait state is entered:
 *
 * <pre>{@code <timeDuration>${workItemSla.breachDuration(execution, 'manualInput')}</timeDuration>}</pre>
 *
 * <p><strong>The bean name is an API</strong>, like the two delegates: the
 * builder's compiler emits it into every manual step that carries a time target,
 * and the node reference travels as a string literal because the compiler knows
 * the task id it is attaching the timer to — no engine introspection, no
 * convention about boundary-event naming.
 *
 * <p><strong>A step with no configured threshold still gets a timer</strong> —
 * the sentinel, ten years out. BPMN's boundary event is static: it exists on the
 * compiled process whether or not this installation configured a threshold, and
 * an expression returning null fails the activity's entry — which would break the
 * manual step exactly when a screen is not yet configured. The ten-year job costs
 * one row in the engine's timer table and is deleted with the task; the honest
 * alternative (compile the timer only when a threshold exists) would freeze
 * threshold configuration into the published process. The sentinel and its cost
 * are part of {@code docs/BPMN_EXECUTION_PROFILE.md}.
 */
@Slf4j
public class WorkItemSla {

	/** Effectively "no SLA": far enough that no visit lives to see it, and deleted with the task. */
	static final Duration NO_SLA_SENTINEL = Duration.ofDays(3_650);

	private final WorkItemIntake workItems;
	private final String siteExternalId;

	public WorkItemSla(WorkItemIntake workItems, String siteExternalId) {
		this.workItems = workItems;
		this.siteExternalId = siteExternalId;
	}

	/** @return the breach threshold as ISO-8601, for the engine's timer parser. */
	public String breachDuration(DelegateExecution execution, String nodeReference) {
		String processDefinitionKey = keyOf(execution.getProcessDefinitionId());
		Duration duration = ScopeContext.callIn(scope(),
				() -> workItems.slaBreachAfter(processDefinitionKey, nodeReference))
				.orElse(NO_SLA_SENTINEL);
		log.debug("arming SLA timer on {}:{} at {}", processDefinitionKey, nodeReference, duration);
		return duration.toString();
	}

	private Scope scope() {
		return Scope.builder()
				.permit("site_external_id", Set.of(siteExternalId))
				.permit("config_realm", Set.of("INSTALLATION"))
				.build();
	}

	private static String keyOf(String processDefinitionId) {
		int colon = processDefinitionId == null ? -1 : processDefinitionId.indexOf(':');
		return colon < 0 ? processDefinitionId : processDefinitionId.substring(0, colon);
	}
}
