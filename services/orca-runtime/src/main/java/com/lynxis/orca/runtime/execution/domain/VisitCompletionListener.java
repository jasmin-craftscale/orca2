package com.lynxis.orca.runtime.execution.domain;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.delegate.event.FlowableActivityEvent;

import lombok.RequiredArgsConstructor;

/**
 * Notices that a process instance reached an end event, and closes its visit —
 * when, and only when, the end event actually ended the instance.
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
 * <p><strong>Why two engine event types are required.</strong> The first version
 * closed the visit on {@code ACTIVITY_COMPLETED} of any {@code endEvent}. That was
 * correct while every end event ended the process, and became wrong when
 * gate-visit gained a NON-INTERRUPTING SLA branch: its breach path
 * concludes at its own end event <em>while the manual-input task still waits</em>,
 * and closing the visit there would mark a truck resolved that is still standing
 * at the gate. So the end event's id is <em>stashed</em> per instance on
 * {@code ACTIVITY_COMPLETED}, and the visit closes on {@code PROCESS_COMPLETED} —
 * the event the engine only fires when the instance genuinely ended, in the same
 * command and the same transaction as the final end event. The id still comes
 * from the end event, never from variables. ({@code PROCESS_COMPLETED} alone
 * does not carry the end activity's id — measured, not assumed; the stash is what
 * bridges the two events.)
 *
 * <p>The stash is bounded: an entry is written per end event and removed when the
 * instance completes or is cancelled (lane reset). An instance that ends neither
 * way does not exist.
 *
 * <p>⚠️ <strong>A residual race, stated rather than hidden</strong> (pre-handover
 * review, finding 4): the stash is JVM state written inside engine commands but
 * not rolled back with them. Two commands on one instance can run concurrently —
 * the async SLA-breach branch beside an HTTP complete — and a command that stashes
 * its end-event id and then rolls back (optimistic lock) can leave a phantom id
 * for the committing command to consume. In every process the current dialect can
 * express this is harmless: the racing pair's end events all classify identically
 * (non-released → {@code MANUAL}), and a rolled-back <em>final</em> end event is
 * retried by the executor, re-stamping the stash. It becomes real only if a
 * compiler emits a process where a <em>differently-classified</em> end
 * ({@code visitReleased}) races a non-interrupting branch's end. The
 * {@code docs/BPMN_EXECUTION_PROFILE.md} dialect forbids that shape until the
 * platform persists this bridge in the database instead of JVM memory.
 */
@RequiredArgsConstructor
public class VisitCompletionListener implements FlowableEventListener {

	private static final String END_EVENT = "endEvent";

	private final VisitCompletion completion;

	private final java.util.concurrent.ConcurrentMap<String, String> lastEndEventByInstance =
			new java.util.concurrent.ConcurrentHashMap<>();

	@Override
	public void onEvent(FlowableEvent event) {
		if (event.getType() == FlowableEngineEventType.ACTIVITY_COMPLETED
				&& event instanceof FlowableActivityEvent activity
				&& END_EVENT.equals(activity.getActivityType())) {
			lastEndEventByInstance.put(activity.getProcessInstanceId(), activity.getActivityId());
			return;
		}
		if (isProcessCompletion(event.getType())
				&& event instanceof org.flowable.common.engine.api.delegate.event.FlowableEngineEvent engineEvent) {
			String processInstanceId = engineEvent.getProcessInstanceId();
			String endEventId = lastEndEventByInstance.remove(processInstanceId);
			if (endEventId == null) {
				// An instance can complete without a visible end event only if it was
				// started and finished outside this listener's lifetime; nothing to
				// classify, and VisitCompletion's own lookup decides if a visit exists.
				endEventId = "";
			}
			completion.reachedEndState(processInstanceId, endEventId);
			return;
		}
		if (event.getType() == FlowableEngineEventType.PROCESS_CANCELLED
				&& event instanceof org.flowable.common.engine.api.delegate.event.FlowableEngineEvent engineEvent) {
			// Lane reset or an operator abort: the reset owns the visit's closing
			// write; this only keeps the stash bounded.
			lastEndEventByInstance.remove(engineEvent.getProcessInstanceId());
		}
	}

	/**
	 * All the ways Flowable says "this instance is over". The engine fires a
	 * VARIANT instead of plain {@code PROCESS_COMPLETED} when the final end event
	 * is a terminate, error or escalation end event. None exists in the current
	 * {@code docs/BPMN_EXECUTION_PROFILE.md} dialect, but this listener is platform behavior for every
	 * process a compiler will ever emit, and a variant it ignored would end an
	 * instance whose visit never closes, silently. Found by the pre-handover
	 * review; verified against the Flowable 8 event-type enum.
	 */
	private static boolean isProcessCompletion(
			org.flowable.common.engine.api.delegate.event.FlowableEventType type) {
		return type == FlowableEngineEventType.PROCESS_COMPLETED
				|| type == FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT
				|| type == FlowableEngineEventType.PROCESS_COMPLETED_WITH_ERROR_END_EVENT
				|| type == FlowableEngineEventType.PROCESS_COMPLETED_WITH_ESCALATION_END_EVENT;
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
