package com.lynxis.orca.runtime.execution.engine.flowable;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.delegate.event.FlowableActivityEvent;
import org.flowable.engine.delegate.event.FlowableProcessStartedEvent;
import org.flowable.engine.runtime.ProcessInstance;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.persistence.NodeExecutionTraceRepository;

/**
 * The write-behind of the step trace: every ORCA step the engine completes lands
 * as a {@code node_execution} row <b>in the engine's own transaction</b> — the
 * engine advances and the record commits, or neither does.
 *
 * <p>Division of labour, and it is deliberate: <em>admission</em> creates the
 * visit row and starts the instance in one transaction; the <em>visit-completion
 * listener</em> closes root visits and records their outbound fact. This recorder
 * owns only what neither of them sees — the per-step trace, and the lifecycle of
 * CHILD executions (a subflow or iterator instance is its own engine instance but
 * not its own admission).
 *
 * <p>A step whose visit row is missing FAILS the step ({@code isFailOnException})
 * — a recorded engine advance with no ORCA record is exactly the silent defect
 * class this write-behind exists to kill.
 *
 * <p>Only ORCA nodes are recorded: element ids carrying the compiler's
 * {@code n_<uuid>} prefix. Synthetic gateways and flows are compilation
 * artifacts, not steps.
 */
public final class NodeExecutionRecorder implements FlowableEventListener {

	private final NodeExecutionTraceRepository trace;
	private final Scope installationScope;

	public NodeExecutionRecorder(NodeExecutionTraceRepository trace, String siteExternalId) {
		this.trace = trace;
		this.installationScope = Scope.of("site_external_id", Set.of(siteExternalId));
	}

	private final ThreadLocal<String> siteOfScope = new ThreadLocal<>();

	@Override
	public void onEvent(FlowableEvent event) {
		if (event instanceof FlowableProcessStartedEvent started
				&& started.getEntity() instanceof ProcessInstance instance
				&& started.getNestedProcessInstanceId() != null) {
			// A child instance: record its execution row under its parent. Root
			// instances are skipped on purpose — admission created their row
			// before the engine ever saw them.
			recordChild(((FlowableEngineEvent) started).getProcessInstanceId(), instance,
					started.getNestedProcessInstanceId());
			return;
		}
		if (event.getType() == FlowableEngineEventType.ACTIVITY_COMPLETED
				&& event instanceof FlowableActivityEvent activity) {
			recordStep(activity);
			return;
		}
		if ((event.getType() == FlowableEngineEventType.PROCESS_COMPLETED
				|| event.getType() == FlowableEngineEventType.PROCESS_CANCELLED)
				&& event instanceof FlowableEngineEvent ended) {
			// Zero rows is the ordinary answer for a root visit — the completion
			// path owns those. The predicate only ever matches children.
			ScopeContext.runIn(installationScope, () ->
					trace.completeChildByEngineInstance(ended.getProcessInstanceId(), Instant.now()));
		}
	}

	private void recordChild(String processInstanceId, ProcessInstance instance,
			String superProcessInstanceId) {
		DefinitionRef definition = DefinitionRef.parse(instance.getProcessDefinitionId());
		ScopeContext.runIn(installationScope, () -> {
			NodeExecutionTraceRepository.VisitRef parent = trace
					.visitByEngineInstance(superProcessInstanceId)
					.orElseThrow(() -> new IllegalStateException(
							"no execution row correlates parent engine instance '"
									+ superProcessInstanceId + "' — a child would be orphaned"));
			trace.insertChildExecution(UUID.randomUUID().toString(),
					siteOf(), parent.laneId(), parent.executionId(),
					definition.workflowId(), definition.version(), processInstanceId);
		});
	}

	private void recordStep(FlowableActivityEvent activity) {
		String elementId = activity.getActivityId();
		if (elementId == null || !elementId.startsWith("n_")) {
			return;
		}
		String nodeUuid = elementId.substring(2);
		ScopeContext.runIn(installationScope, () -> {
			NodeExecutionTraceRepository.VisitRef visit = trace
					.visitByEngineInstance(activity.getProcessInstanceId())
					.orElseThrow(() -> new IllegalStateException(
							"no execution row correlates engine instance '"
									+ activity.getProcessInstanceId() + "' for step " + elementId
									+ " — the write-behind would go silent"));
			// What the step carried, if whoever produced it left it in the
			// lookaside. One writer, one row: producers never write the trace.
			String payload = StepPayloads.take(activity.getProcessInstanceId(), elementId);
			trace.recordCompletedStep(UUID.randomUUID().toString(), siteOf(),
					visit.executionId(), nodeUuid, orcaType(activity.getActivityType()),
					payload, Instant.now());
		});
	}

	private String siteOf() {
		return installationScope.permitted("site_external_id").iterator().next();
	}

	/** BPMN construct → the ORCA node type the estate's queries speak. */
	private static String orcaType(String activityType) {
		return switch (activityType == null ? "" : activityType) {
			case "startEvent" -> "START";
			case "endEvent" -> "TERMINATOR";
			case "serviceTask" -> "CONNECTOR";
			case "userTask" -> "MANUAL_INPUT";
			case "callActivity" -> "PROCESS";
			case "exclusiveGateway" -> "DECISION";
			default -> activityType == null ? "UNKNOWN" : activityType.toUpperCase(java.util.Locale.ROOT);
		};
	}

	/** The compiler's definition key, {@code proc_<workflowId>}, plus the engine's version. */
	private record DefinitionRef(Long workflowId, Integer version) {

		static DefinitionRef parse(String processDefinitionId) {
			if (processDefinitionId == null) {
				return new DefinitionRef(null, null);
			}
			String[] parts = processDefinitionId.split(":");
			Long workflowId = null;
			Integer version = null;
			if (parts[0].startsWith("proc_")) {
				try {
					workflowId = Long.parseLong(parts[0].substring("proc_".length()));
				}
				catch (NumberFormatException notNumeric) {
					// A hand-written definition; its key carries no workflow id.
				}
			}
			if (parts.length > 1) {
				try {
					version = Integer.parseInt(parts[1]);
				}
				catch (NumberFormatException notNumeric) {
					// Same: absence is the honest value.
				}
			}
			return new DefinitionRef(workflowId, version);
		}
	}

	@Override
	public boolean isFailOnException() {
		return true;
	}

	@Override
	public boolean isFireOnTransactionLifecycleEvent() {
		return false;
	}

	@Override
	public String getOnTransaction() {
		return null;
	}
}
