package com.lynxis.orca.runtime.execution.persistence;

import java.util.function.Supplier;

import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.TaskService;

import com.lynxis.orca.runtime.execution.api.ManualStepPort;

import lombok.RequiredArgsConstructor;

/**
 * The engine's side of complete-and-advance — the only place a work item's
 * completion touches Flowable, kept inside {@code execution} where
 * {@code EngineConfinementRule} allows it.
 *
 * <p>{@code taskService.complete} runs the process <em>synchronously in the
 * caller's transaction</em> until the next wait state or async job. For the
 * gate-visit shape that is the resolved end event, so the item's update, the
 * engine's advance and the visit's own closing write share one commit — which is
 * inversion 1, mechanically.
 *
 * <p>The {@link TaskService} arrives as a {@link Supplier}, resolved on first use
 * rather than at construction, and the reason is a genuine cycle: the engine's
 * listener registrar needs {@code WorkItemIntake}, the intake needs this port,
 * and a port that demanded {@code TaskService} at construction would demand the
 * engine — which is waiting for the registrar. First use is always after startup,
 * so the lazy hop costs nothing on any request.
 */
@RequiredArgsConstructor
public class FlowableManualSteps implements ManualStepPort {

	private final Supplier<TaskService> taskService;

	@Override
	public void completeManualStep(String taskId) throws ProcessNotWaitingException {
		try {
			taskService.get().complete(taskId);
		}
		catch (FlowableObjectNotFoundException notWaiting) {
			// The out-of-order submit (inversion 3). The caller's transaction — and
			// the item update inside it — rolls back with this.
			throw new ProcessNotWaitingException(taskId, notWaiting);
		}
		catch (FlowableException engineRefused) {
			// A suspended task or definition arrives as a different Flowable type;
			// either way the engine is not accepting this submit, and refusing whole
			// beats guessing which half applied.
			throw new ProcessNotWaitingException(taskId, engineRefused);
		}
	}
}
