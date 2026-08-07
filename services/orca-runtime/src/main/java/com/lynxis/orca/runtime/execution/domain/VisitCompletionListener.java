package com.lynxis.orca.runtime.execution.domain;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.delegate.event.FlowableActivityEvent;

import lombok.RequiredArgsConstructor;

/**
 * Notices that a process instance reached an end event, and closes its visit.
 *
 * <p><strong>Why an engine listener and not a third service task.</strong> The
 * compiled process is the visual builder's output, and every construct this
 * repository uses becomes something that compiler must emit. Making completion a
 * service task would require <em>every</em> process an administrator ever designs
 * to end with one particular bean call, and a designer who deleted it would produce
 * a process that ran perfectly and left every visit open forever. Completion is
 * platform behaviour, not process design, so it belongs where the platform can
 * guarantee it.
 *
 * <p><strong>Why not an execution listener on the end event.</strong> Same reason,
 * plus {@code flowable:executionListener} is a proprietary extension and the
 * profile admits exactly one ({@code delegateExpression}).
 *
 * <p>{@code ACTIVITY_COMPLETED} on an {@code endEvent} rather than
 * {@code PROCESS_COMPLETED}, because the end event's <em>id</em> is what
 * distinguishes "the truck may go" from "a human is needed" — and
 * {@code PROCESS_COMPLETED} would force that distinction to be inferred from
 * variables, which is guessing dressed as reading.
 */
@RequiredArgsConstructor
public class VisitCompletionListener implements FlowableEventListener {

	private static final String END_EVENT = "endEvent";

	private final VisitCompletion completion;

	@Override
	public void onEvent(FlowableEvent event) {
		if (event.getType() != FlowableEngineEventType.ACTIVITY_COMPLETED
				|| !(event instanceof FlowableActivityEvent activity)
				|| !END_EVENT.equals(activity.getActivityType())) {
			return;
		}
		completion.reachedEndState(activity.getProcessInstanceId(), activity.getActivityId());
	}

	/**
	 * <strong>True, and it is the important line in this class.</strong>
	 *
	 * <p>False would let the engine complete the instance while this listener's
	 * failure was swallowed — leaving a visit that the process finished and the
	 * platform never closed, with no fact recorded and nothing to detect it. True
	 * fails the job, which the engine retries; the visit stays open and visible in
	 * the meantime, which is the honest state.
	 */
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
