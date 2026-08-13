package com.lynxis.orca.runtime.execution.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.idempotency.IdempotencyOutcome;
import com.lynxis.orca.platform.idempotency.IdempotencyStore;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.readmodel.api.LaneMonitorProjectionPort;

import lombok.extern.slf4j.Slf4j;

/**
 * Enforces <strong>one truck, one visit</strong>, the admission property on which
 * the gate's process model depends.
 *
 * <p>Two device events for the same truck can arrive in the same millisecond, from
 * two instances. Exactly one visit must start. That is not achievable by reading
 * and then writing: between the read and the write, the other instance does both.
 * So admission is <em>one atomic correlate-or-start</em>, held by three things in
 * order:
 *
 * <ol>
 *   <li><strong>The lane lock.</strong> An exclusive row lock on the lane's own
 *       {@code lane_session} row. Every admission for a lane serialises there —
 *       and only there, so a busy lane never blocks a quiet one.</li>
 *   <li><strong>The filtered unique index</strong> on {@code execution (lane_id)
 *       WHERE status = 'ACTIVE' AND parent_execution_id IS NULL}. The lock is the
 *       mechanism; this is what keeps the property true when a future inbound path
 *       forgets the lock. When it fires, the loser correlates rather than
 *       failing.</li>
 *   <li><strong>One transaction.</strong> The idempotency claim, the insert, the
 *       engine start and the event row commit together or not at all. Flowable
 *       runs on the same database and the same Spring transaction, which is what
 *       makes that possible without a distributed transaction.</li>
 * </ol>
 *
 * <p>{@code AdmissionPropertiesIT} proves all three against real Flowable 8 and
 * SQL Server: 1,000 iterations with two simultaneous events each produce exactly
 * 1,000 visits, and with the lane lock removed the unique-index backstop fires in
 * 99 runs out of 100.
 *
 * <h2>Why the idempotency claim is inside the transaction</h2>
 *
 * <p>Claiming outside it leaves a window: the visit commits, the process dies
 * before the outcome is recorded, and the key stays {@code IN_PROGRESS} forever —
 * so every redelivery of that event is told to keep waiting for a run that ended.
 * Inside, the claim, the effect and the recorded outcome share one commit, and
 * there is no state in which one exists without the others.
 *
 * <p><strong>Deadlock retry is part of the design, not the test's tolerance.</strong>
 * SQL Server resolves a lock cycle by rolling one transaction back with error
 * 1205, on a schedule of its own. An admission that gave up there would drop a
 * truck's event for a reason that has nothing to do with the truck, so the victim
 * retries — and because the whole operation is one transaction, retrying it is
 * safe by construction rather than by argument.
 */
@Slf4j
public class AdmissionService {

	/** The one process definition currently assigned to every lane. */
	public static final String PROCESS_KEY = "gate-visit";

	/** The device-event idempotency operation; each producer kind has its own namespace. */
	public static final String OPERATION = "device-event";

	/**
	 * How many times a deadlock victim retries before the event is genuinely
	 * refused. Five, because a cycle that survives five independent rollbacks is not
	 * a scheduling accident, and retrying it forever would hide a real defect.
	 */
	private static final int MAX_ATTEMPTS = 5;

	private final AdmissionRepository repository;
	private final ProcessEngineGateway engine;
	private final IdempotencyStore idempotency;
	private final TransactionTemplate transactions;
	private final String siteExternalId;
	private final String holderId;
	private final ProcessStartVariables startVariables;
	private final LaneMonitorProjectionPort laneMonitor;

	/** Reported, not asserted on: how often SQL Server chose a victim during a run. */
	private final AtomicInteger deadlockRetries = new AtomicInteger();

	public AdmissionService(AdmissionRepository repository, ProcessEngineGateway engine,
			IdempotencyStore idempotency, TransactionTemplate transactions, String siteExternalId,
			String holderId, ProcessStartVariables startVariables, LaneMonitorProjectionPort laneMonitor) {
		this.repository = repository;
		this.engine = engine;
		this.idempotency = idempotency;
		this.transactions = transactions;
		this.siteExternalId = siteExternalId;
		this.holderId = holderId;
		this.startVariables = startVariables;
		this.laneMonitor = laneMonitor;
	}

	/**
	 * Accepts a batch, in the order it was sent.
	 *
	 * <p><strong>Every lane is resolved before any event is admitted.</strong> That
	 * pre-pass is the whole reason this method exists rather than a loop at the call
	 * site: each event is admitted in its own transaction. The lane lock is released
	 * at commit; one transaction spanning a hundred
	 * admissions would hold a hundred lanes shut — so an event refused halfway
	 * through would leave the earlier ones committed while the caller was told the
	 * batch failed. Edge acknowledges a batch or none of it, and would resend the
	 * lot.
	 *
	 * <p>⚠️ One narrow race survives this, and it is stated rather than closed: a
	 * lane retired between the pre-pass and its own admission fails mid-batch. The
	 * redelivery is what covers it — the events already admitted answer
	 * {@code DUPLICATE} and produce no second effect.
	 *
	 * @throws LaneNotAtThisInstallationException when any event names a lane this
	 *         installation's site does not publish. Refused rather than admitted
	 *         onto a guessed lane
	 */
	public java.util.List<EventOutcome> acceptBatch(java.util.List<InboundDeviceEvent> events) {
		for (InboundDeviceEvent event : events) {
			repository.laneIdOf(event.laneExternalId())
					.orElseThrow(() -> new LaneNotAtThisInstallationException(event.laneExternalId()));
		}
		return events.stream().map(this::accept).toList();
	}

	/**
	 * Accepts one device event, exactly once.
	 *
	 * @throws LaneNotAtThisInstallationException when the lane is not one this
	 *         installation's site publishes. Refused rather than admitted onto a
	 *         guessed lane
	 */
	public EventOutcome accept(InboundDeviceEvent event) {
		return accept(event, OPERATION);
	}

	/**
	 * Accepts one event in the operation namespace owned by its inbound adapter.
	 * Package-private so a caller outside {@code execution} cannot choose a namespace
	 * and accidentally make one producer answer for another.
	 */
	EventOutcome accept(InboundDeviceEvent event, String operation) {
		long laneId = repository.laneIdOf(event.laneExternalId())
				.orElseThrow(() -> new LaneNotAtThisInstallationException(event.laneExternalId()));

		// Its own transaction, before the one that admits: this is the row every
		// admission for the lane locks, so creating it inside the admitting
		// transaction would make the first two trucks on a fresh lane race on the
		// creation rather than serialise on the row.
		transactions.executeWithoutResult(status ->
				repository.createLaneSessionIfAbsent(laneId, siteExternalId, event.laneExternalId()));

		for (int attempt = 1; ; attempt++) {
			try {
				return transactions.execute(status -> acceptOnce(event, laneId, operation));
			}
			catch (PessimisticLockingFailureException victim) {
				// 1205 (deadlock victim) or 1222 (lock request timeout). The server has
				// already rolled this transaction back, so there is nothing to undo and
				// nothing half-applied to reason about — including the idempotency
				// claim, which rolled back with it.
				if (attempt >= MAX_ATTEMPTS) {
					throw victim;
				}
				deadlockRetries.incrementAndGet();
				backOff(attempt);
			}
		}
	}

	private EventOutcome acceptOnce(InboundDeviceEvent event, long laneId, String operation) {
		// --- 0 · take the lane, THEN claim the event ----------------------------
		// Coarse resource before fine, in that order on every path. The other order
		// was tried first and it deadlocks: two events for one truck each claim their
		// own key, then contend for the lane, while each still holds locks the other
		// needs to record its own outcome. This was measured: an eight-lane run
		// exhausted its retries until the order was inverted.
		//
		// The cost is that a redelivered event takes the lane lock before it
		// discovers it is a duplicate. That is one row lock held for one read, on the
		// path that is already the rare one.
		if (!repository.lockLane(laneId)) {
			// The session row was created a moment ago and is gone, or another
			// instance is creating it. Either way this admission has nothing to
			// serialise on, and proceeding would be admitting without the lock.
			throw new IllegalStateException("Lane " + laneId + " has no lane_session row to lock. "
					+ "Admission without the lane lock is not a mode this operation has.");
		}

		IdempotencyOutcome claim = idempotency.begin(event.eventUuid(), operation, holderId);
		switch (claim) {
			case IdempotencyOutcome.Completed completed -> {
				// NOT an error. The caller retried because it never saw the first
				// answer, so a rejection is the one response it cannot use.
				return EventOutcome.duplicate(event.eventUuid(), completed.outcome());
			}
			case IdempotencyOutcome.InProgress inProgress -> {
				log.debug("event {} is already being admitted by {}", event.eventUuid(),
						inProgress.holderId());
				return EventOutcome.inProgress(event.eventUuid());
			}
			case IdempotencyOutcome.Fresh ignored -> {
				// Ours to run.
			}
		}

		Admission admission = admit(event, laneId);
		repository.attachEvent(event.eventUuid(), siteExternalId, admission.executionId(), laneId,
				event.eventType(), event.deviceExternalId(), event.attributes(), event.occurredAt());
		laneMonitor.recordDeviceEvent(new LaneMonitorProjectionPort.DeviceEventObserved(siteExternalId,
				laneId, event.laneExternalId(), event.eventUuid(), event.eventType(),
				event.attributes(), event.occurredAt()));
		idempotency.complete(event.eventUuid(), operation, admission.visitExternalId());

		return EventOutcome.admitted(event.eventUuid(), admission);
	}

	/** Called with the lane already locked by {@link #acceptOnce}. */
	private Admission admit(InboundDeviceEvent event, long laneId) {
		String plate = plateOf(event);

		// --- 1 · correlate -----------------------------------------------------
		// Under the lane lock this read cannot race: whoever holds the lock is the
		// only one who can be between reading and inserting.
		Optional<AdmissionRepository.ActiveVisit> running = repository.activeRootOn(laneId);
		if (running.isPresent()) {
			repository.bindLane(laneId, plate, false);
			return new Admission.Correlated(running.get().executionId(), running.get().externalId());
		}

		// --- 2 · start ---------------------------------------------------------
		String visitExternalId = "vis-" + UUID.randomUUID();
		long executionId;
		try {
			executionId = repository.insertVisit(visitExternalId, siteExternalId, laneId, plate);
		}
		catch (DuplicateKeyException backstopFired) {
			// Unreachable while every inbound path takes the lane lock — and that is
			// exactly why it is handled rather than propagated. The index refused a
			// second active root visit; the event still belongs to the one that exists.
			log.warn("the filtered unique index refused a second active root visit on lane {}. "
					+ "Some inbound path admitted without the lane lock", laneId);
			AdmissionRepository.ActiveVisit winner = repository.activeRootOn(laneId)
					.orElseThrow(() -> backstopFired);
			repository.bindLane(laneId, plate, false);
			return new Admission.Correlated(winner.executionId(), winner.externalId());
		}

		// --- 3 · the engine, in the SAME transaction ---------------------------
		// Not "start the process after the commit": between the two there is a window
		// in which a visit row exists that no engine instance is running, and nothing
		// would ever detect it. Flowable's tables are in this schema and this
		// transaction, so the two commit together.
		String processInstanceId = engine.startVisit(PROCESS_KEY, visitExternalId,
				correlationKeys(visitExternalId, event.laneExternalId()));

		repository.recordProcessInstance(executionId, processInstanceId);
		repository.bindLane(laneId, plate, true);
		laneMonitor.recordVisitStarted(new LaneMonitorProjectionPort.VisitStarted(siteExternalId,
				laneId, event.laneExternalId(), visitExternalId, plate));

		return new Admission.Started(executionId, visitExternalId, processInstanceId);
	}

	/**
	 * The variables the compiled process is started with.
	 *
	 * <p>Correlation keys and short branch/configuration tokens only. Which connector to call and which
	 * action to issue are <em>configuration</em> here rather than fields on the BPMN
	 * — see {@link ProcessStartVariables} for why that is a slice-shaped decision
	 * and not the target shape.
	 */
	private Map<String, Object> correlationKeys(String visitExternalId, String laneExternalId) {
		Map<String, Object> keys = new HashMap<>();
		keys.put(ProcessVariables.VISIT_EXTERNAL_ID, visitExternalId);
		keys.put(ProcessVariables.LANE_EXTERNAL_ID, laneExternalId);
		keys.put(ProcessVariables.CONNECTOR_NAME, startVariables.connectorName());
		keys.put(ProcessVariables.COMMAND_ACTION, startVariables.commandAction());
		keys.put(ProcessVariables.COMMAND_DEVICE_EXTERNAL_ID, startVariables.commandDeviceExternalId());
		keys.put(ProcessVariables.COMMAND_DEADLINE_MILLIS, startVariables.commandDeadlineMillis());
		return keys;
	}

	/**
	 * The plate, out of the normalised attributes.
	 *
	 * <p>Read with a narrow scan rather than a JSON parser: this is the one key
	 * runtime reads out of a map edge owns, and binding a parser here would be the
	 * first step towards runtime taking a position on the rest of it. An event
	 * without a plate is still an event — it admits, with no plate bound.
	 */
	private static String plateOf(InboundDeviceEvent event) {
		String attributes = event.attributes();
		if (attributes == null) {
			return null;
		}
		int key = attributes.indexOf("\"plate\"");
		if (key < 0) {
			return null;
		}
		int open = attributes.indexOf('"', attributes.indexOf(':', key) + 1);
		int close = open < 0 ? -1 : attributes.indexOf('"', open + 1);
		return close < 0 ? null : attributes.substring(open + 1, close);
	}

	/** Reported, not asserted on: how often SQL Server chose a victim during a run. */
	public int deadlockRetries() {
		return deadlockRetries.get();
	}

	private static void backOff(int attempt) {
		try {
			// Enough jitter that two victims do not re-collide in lockstep.
			Thread.sleep(attempt * 5L + (long) (Math.random() * 10));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while backing off a deadlock retry", interrupted);
		}
	}

	/**
	 * What happened to one event in a batch.
	 *
	 * @param admission the admission this delivery performed, or null when a
	 *                  previous one did
	 */
	public record EventOutcome(String eventUuid, Status status, String visitExternalId, Admission admission) {

		public enum Status { STARTED, CORRELATED, DUPLICATE, IN_PROGRESS }

		static EventOutcome admitted(String eventUuid, Admission admission) {
			Status status = admission instanceof Admission.Started ? Status.STARTED : Status.CORRELATED;
			return new EventOutcome(eventUuid, status, admission.visitExternalId(), admission);
		}

		static EventOutcome duplicate(String eventUuid, String recordedOutcome) {
			return new EventOutcome(eventUuid, Status.DUPLICATE, recordedOutcome, null);
		}

		static EventOutcome inProgress(String eventUuid) {
			return new EventOutcome(eventUuid, Status.IN_PROGRESS, null, null);
		}

		/** Whether this delivery is what admitted the event, as opposed to learning that one had. */
		public boolean isNewlyAdmitted() {
			return status == Status.STARTED || status == Status.CORRELATED;
		}
	}

	/**
	 * What the compiled process is started with, from configuration.
	 *
	 * <p>⚠️ <strong>Slice shape, not target shape.</strong> In the product these come
	 * from the process definition itself — the visual builder's compiler emits a
	 * connector step naming its connector and a device step naming its action, while
	 * lane configuration selects the definition. There is one process here, so
	 * carrying them as configuration keeps admission from inventing a
	 * definition-to-lane binding that the future visual builder still has to design.
	 */
	public record ProcessStartVariables(String connectorName, String commandAction,
			String commandDeviceExternalId, long commandDeadlineMillis) {
	}

	/** The lane is not one this installation's site publishes. */
	public static class LaneNotAtThisInstallationException extends RuntimeException {

		private final String laneExternalId;

		public LaneNotAtThisInstallationException(String laneExternalId) {
			super("Lane '" + laneExternalId + "' is not published by core for this installation's site. "
					+ "The event is refused rather than admitted onto a guessed lane.");
			this.laneExternalId = laneExternalId;
		}

		public String laneExternalId() {
			return laneExternalId;
		}
	}
}
