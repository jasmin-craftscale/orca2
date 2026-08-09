package com.lynxis.orca.runtime.execution.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;

import com.lynxis.orca.runtime.execution.domain.ProcessEngineGateway;

import lombok.RequiredArgsConstructor;

/**
 * The one place Flowable's API is spoken.
 *
 * <p>{@code EngineConfinementRule} fails the build if any class outside
 * {@code runtime.execution} imports {@code org.flowable}. This class and the two
 * delegates are why the rule is worth having rather than trivially true: they are
 * the only production code that has a reason to, and the rule stops the fourth
 * such class from appearing somewhere else because it was convenient once.
 */
@RequiredArgsConstructor
public class FlowableProcessEngineGateway implements ProcessEngineGateway {

	private final RuntimeService runtimeService;

	@Override
	public String startVisit(String processKey, String businessKey, Map<String, Object> correlationKeys) {
		ProcessInstance instance =
				runtimeService.startProcessInstanceByKey(processKey, businessKey, correlationKeys);
		return instance.getId();
	}

	@Override
	public boolean isRunning(String processInstanceId) {
		return runtimeService.createProcessInstanceQuery()
				.processInstanceId(processInstanceId)
				.count() > 0;
	}

	@Override
	public void terminate(String processInstanceId, String reason) {
		try {
			runtimeService.deleteProcessInstance(processInstanceId, reason);
		}
		catch (org.flowable.common.engine.api.FlowableObjectNotFoundException alreadyGone) {
			// A reset retried after a crash, or a race with the instance finishing on
			// its own. The instance not existing is the state the caller wanted.
		}
	}

	@Override
	public Optional<String> currentActivity(String processInstanceId) {
		List<Execution> executions = runtimeService.createExecutionQuery()
				.processInstanceId(processInstanceId)
				.list();
		return executions.stream()
				.map(Execution::getActivityId)
				.filter(java.util.Objects::nonNull)
				.findFirst();
	}
}
