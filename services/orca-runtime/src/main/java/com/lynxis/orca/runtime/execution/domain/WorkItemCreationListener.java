package com.lynxis.orca.runtime.execution.domain;

import java.util.Set;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.task.api.Task;
import org.flowable.variable.api.delegate.VariableScope;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Notices that the engine is parking at a manual-input wait state and creates the
 * work item <strong>in the same transaction</strong>. This reverses the legacy 1.x
 * ownership: the process reaches a wait state first, then runtime creates the
 * clerk's item atomically with that wait.
 *
 * <p><strong>Why an engine listener and not a task listener on the BPMN.</strong>
 * The same argument as {@link VisitCompletionListener}: the compiled process is the
 * visual builder's output, and a designer who could omit the listener would
 * produce a process that parks forever with no item in any queue — invisible human
 * work, the worst failure this module can have. Work-item creation is platform
 * behaviour, not process design, so it is attached to the engine once, at startup,
 * for every {@code userTask} in every process any administrator ever designs. The
 * BPMN needs nothing but the plain {@code userTask} wait state defined by
 * {@code docs/BPMN_EXECUTION_PROFILE.md}.
 *
 * <p><strong>{@code isFailOnException() == true} is the atomicity property.</strong>
 * If the item cannot be written, the engine does not park: the whole job rolls
 * back — task, timer jobs, item — and is retried together. There is no state where
 * the process waits and no item exists, or an item exists and the process did not
 * park. {@code WorkItemLifecycleIT} injects exactly that fault and watches both
 * halves disappear.
 */
@Slf4j
@RequiredArgsConstructor
public class WorkItemCreationListener implements FlowableEventListener {

	private final WorkItemIntake intake;
	private final AdmissionRepository repository;
	private final String siteExternalId;

	@Override
	public void onEvent(FlowableEvent event) {
		if (event.getType() != FlowableEngineEventType.TASK_CREATED
				|| !(event instanceof FlowableEntityEvent entityEvent)
				|| !(entityEvent.getEntity() instanceof Task task)) {
			return;
		}
		// The engine's worker has an identity but no scope — the same deliberate
		// act VisitCompletion performs before it touches a scoped table.
		ScopeContext.runIn(Scope.of("site_external_id", Set.of(siteExternalId)),
				() -> raise(task));
	}

	private void raise(Task task) {
		AdmissionRepository.VisitRow visit = repository
				.visitByProcessInstance(task.getProcessInstanceId()).orElse(null);
		if (visit == null) {
			// A user task in a process with no visit row — a probe definition or one
			// deployed by hand. Not this listener's to queue: a work item exists to
			// resolve a truck's visit, and there is no truck.
			log.debug("user task {} in process {} has no visit row; no work item raised",
					task.getId(), task.getProcessInstanceId());
			return;
		}

		String laneExternalId = repository.laneExternalIdOf(visit.laneId())
				.orElse(String.valueOf(visit.laneId()));

		intake.manualStepReached(new WorkItemIntake.ManualStep(
				task.getId(),
				task.getProcessInstanceId(),
				processDefinitionKeyOf(task),
				task.getTaskDefinitionKey(),
				visit.executionId(),
				visit.laneId(),
				laneExternalId,
				visit.externalId(),
				capturedContext(task)));
	}

	/**
	 * The step's captured context: the branch discriminators the process was
	 * carrying when it parked. Discriminators only: process variables may not carry
	 * response bodies, so this context cannot leak one.
	 */
	private static String capturedContext(Task task) {
		if (!(task instanceof VariableScope variables)) {
			return null;
		}
		StringBuilder json = new StringBuilder("{");
		appendIfPresent(json, variables, ProcessVariables.CONNECTOR_OUTCOME);
		appendIfPresent(json, variables, ProcessVariables.DEVICE_COMMAND_OUTCOME);
		return json.append("}").toString();
	}

	private static void appendIfPresent(StringBuilder json, VariableScope variables, String name) {
		Object value = variables.getVariable(name);
		if (value == null) {
			return;
		}
		if (json.length() > 1) {
			json.append(',');
		}
		// Backslash BEFORE quote, or escaping the quote mints new backslashes.
		// Discriminators are short routing tokens derived from connector answers, so
		// this escaping must keep the captured context valid JSON.
		json.append('"').append(name).append("\":\"")
				.append(value.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
	}

	/** {@code gate-visit:3:12034} → {@code gate-visit}. The id format is the engine's own contract. */
	private static String processDefinitionKeyOf(Task task) {
		String definitionId = task.getProcessDefinitionId();
		int colon = definitionId == null ? -1 : definitionId.indexOf(':');
		return colon < 0 ? definitionId : definitionId.substring(0, colon);
	}

	/** True — the atomicity property. See the class comment. */
	@Override
	public boolean isFailOnException() {
		return true;
	}

	/** Not transaction-scoped: this must run INSIDE the engine's transaction, not around it. */
	@Override
	public boolean isFireOnTransactionLifecycleEvent() {
		return false;
	}

	@Override
	public String getOnTransaction() {
		return null;
	}
}
