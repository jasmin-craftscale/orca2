package com.lynxis.orca.runtime.admission;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>WP0 · admission.</strong> One truck, one visit — the property the whole
 * design turns on (§B10, §C2).
 *
 * <p>Two device events for the same truck can arrive in the same millisecond,
 * from two instances. Exactly one visit must start. That is not achievable by
 * reading and then writing: between the read and the write, the other instance
 * does both. So admission is <em>one atomic correlate-or-start</em>, and it is
 * held by three things in order:
 *
 * <ol>
 *   <li><strong>The lane lock.</strong> {@code SELECT … WITH (UPDLOCK, ROWLOCK)}
 *       on the lane's own {@code lane_session} row. Every admission for a lane
 *       serialises there — and only there, so a busy lane never blocks a quiet
 *       one.</li>
 *   <li><strong>The filtered unique index</strong> on
 *       {@code execution (lane_id) WHERE status = 'ACTIVE' AND parent_execution_id
 *       IS NULL}. The lock is the mechanism; this is what keeps the property true
 *       when a future inbound path forgets the lock. When it fires, the loser
 *       correlates rather than failing.</li>
 *   <li><strong>One transaction.</strong> The insert and
 *       {@code startProcessInstanceByKey} commit together or not at all. Flowable
 *       runs on the same database and the same Spring transaction, which is what
 *       makes that possible without a distributed transaction.</li>
 * </ol>
 *
 * <p><strong>Deadlock retry is part of the design, not the test's tolerance.</strong>
 * SQL Server resolves a lock cycle by rolling one transaction back with error
 * 1205, and it does so on a schedule of its own. An admission that gave up there
 * would drop a truck's event for a reason that has nothing to do with the truck,
 * so the victim retries — and because the whole operation is one transaction,
 * retrying it is safe by construction rather than by argument.
 *
 * <p><strong>This class is WP0 shape, not shipping placement.</strong> It uses
 * {@link JdbcTemplate} directly, which a class under {@code src/main} could not:
 * {@code ScopeSeamRule} forbids it, and the seam has no write surface until WP2.
 * WP6 places this operation in {@code runtime.execution} behind the extended seam.
 */
public final class AdmissionOperation {

	/** The process the visit runs. WP4 replaces it with {@code gate-visit}. */
	public static final String PROCESS_KEY = "wp0-admission";

	/**
	 * How many times a deadlock victim retries before the event is genuinely
	 * refused. Five, because a cycle that survives five independent rollbacks is
	 * not a scheduling accident and retrying it forever would hide a real defect.
	 */
	private static final int MAX_ATTEMPTS = 5;

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final RuntimeService runtimeService;

	/** Reported, not asserted on: how often SQL Server chose a victim during a run. */
	private final AtomicInteger deadlockRetries = new AtomicInteger();

	private volatile AdmissionFault fault = AdmissionFault.NONE;

	public AdmissionOperation(JdbcTemplate jdbc, TransactionTemplate transactions, RuntimeService runtimeService) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.runtimeService = runtimeService;
	}

	/**
	 * Correlate-or-start for one inbound device event.
	 *
	 * @param laneId the lane the event arrived on
	 * @param plate  what the camera read
	 */
	public Admission admit(long laneId, String plate) {
		return admit(laneId, plate, true);
	}

	/**
	 * Correlate-or-start with the lane lock deliberately skipped.
	 *
	 * <p>This exists for one reason: a backstop nobody has watched fire is a
	 * backstop nobody knows works. With the lock in place the filtered unique index
	 * is never reached, so the only way to know it refuses a second active root
	 * visit under real concurrency — and that the loser correlates rather than
	 * failing — is to remove the mechanism in front of it and look.
	 *
	 * <p>It is not a mode the shipping operation has. It is the test's way of
	 * simulating a future inbound path that forgot the lock, which is precisely the
	 * case the index is for.
	 */
	public Admission admitBypassingLaneLock(long laneId, String plate) {
		return admit(laneId, plate, false);
	}

	private Admission admit(long laneId, String plate, boolean takeLaneLock) {
		for (int attempt = 1; ; attempt++) {
			try {
				return transactions.execute(status -> admitOnce(laneId, plate, takeLaneLock));
			}
			catch (PessimisticLockingFailureException victim) {
				// 1205 (deadlock victim) or 1222 (lock request timeout). The server
				// has already rolled this transaction back, so there is nothing to
				// undo and nothing half-applied to reason about.
				if (attempt >= MAX_ATTEMPTS) {
					throw victim;
				}
				deadlockRetries.incrementAndGet();
				backOff(attempt);
			}
		}
	}

	private Admission admitOnce(long laneId, String plate, boolean takeLaneLock) {
		// --- 1 · take the lane -------------------------------------------------
		// UPDLOCK rather than a plain read: a shared lock would let both instances
		// read, both find nothing, and both proceed — which is the read-then-write
		// this operation exists to not be. ROWLOCK keeps the escalation off the
		// table, so lane 3's traffic cannot stall lane 4's.
		String lockHint = takeLaneLock ? " WITH (UPDLOCK, ROWLOCK)" : "";
		List<Long> lane = jdbc.query(
				"SELECT lane_id FROM lane_session" + lockHint + " WHERE lane_id = ?",
				(rs, row) -> rs.getLong(1), laneId);
		if (lane.isEmpty()) {
			// An event for a lane the world model does not have. §C2 calls this an
			// unmatched event and says it is made visible rather than dropped; WP6
			// wires that. Failing loudly here is the honest WP0 behaviour.
			throw new IllegalStateException(
					"No lane_session row for lane " + laneId + ". An event arrived for a lane this "
							+ "installation has no session row for — visible, never silently dropped.");
		}

		// --- 2 · correlate -----------------------------------------------------
		// Under the lane lock this read cannot race: whoever holds the lock is the
		// only one who can be between reading and inserting.
		List<Long> running = activeRootOn(laneId);
		if (!running.isEmpty()) {
			jdbc.update("UPDATE lane_session SET bound_plate = ?, updated_at = SYSUTCDATETIME() WHERE lane_id = ?",
					plate, laneId);
			return new Admission.Correlated(running.getFirst(), false);
		}

		// --- 3 · start ---------------------------------------------------------
		String externalId = "vis-" + UUID.randomUUID();
		Long executionId;
		try {
			executionId = jdbc.queryForObject(
					"INSERT INTO execution (external_id, lane_id, parent_execution_id, status, plate) "
							+ "OUTPUT INSERTED.execution_id VALUES (?, ?, NULL, 'ACTIVE', ?)",
					Long.class, externalId, laneId, plate);
		}
		catch (DuplicateKeyException backstopFired) {
			// Unreachable while every inbound path takes the lane lock — and that is
			// exactly why it is handled rather than propagated. The index refused a
			// second active root visit; the event still belongs to the one that
			// exists, so it correlates. Reported as `viaBackstop` so a run in which
			// this happens is not mistaken for an ordinary one.
			List<Long> winner = activeRootOn(laneId);
			if (winner.isEmpty()) {
				throw backstopFired;
			}
			return new Admission.Correlated(winner.getFirst(), true);
		}

		fault.afterInsertBeforeEngineStart(executionId);

		// --- 4 · the engine, in the SAME transaction ---------------------------
		// Not "start the process after the commit": between the two there is a
		// window in which a visit row exists that no engine instance is running,
		// and nothing would ever detect it. Flowable's tables are in this schema
		// and this transaction, so the two commit together.
		ProcessInstance instance = runtimeService.startProcessInstanceByKey(
				PROCESS_KEY, externalId, java.util.Map.of("laneId", laneId, "plate", plate));

		jdbc.update("UPDATE execution SET process_instance_id = ? WHERE execution_id = ?",
				instance.getId(), executionId);
		jdbc.update("UPDATE lane_session SET bound_plate = ?, bound_at = SYSUTCDATETIME(), "
				+ "updated_at = SYSUTCDATETIME() WHERE lane_id = ?", plate, laneId);

		return new Admission.Started(executionId, instance.getId());
	}

	private List<Long> activeRootOn(long laneId) {
		return jdbc.query(
				"SELECT execution_id FROM execution "
						+ "WHERE lane_id = ? AND status = 'ACTIVE' AND parent_execution_id IS NULL",
				(rs, row) -> rs.getLong(1), laneId);
	}

	/** Creates the lane's session row if it has none. Idempotent, and its own transaction. */
	public void ensureLaneSession(long laneId, String laneExternalId) {
		transactions.executeWithoutResult(status -> {
			try {
				jdbc.update("INSERT INTO lane_session (lane_id, lane_external_id) VALUES (?, ?)",
						laneId, laneExternalId);
			}
			catch (DuplicateKeyException alreadyThere) {
				// Two instances seeing a lane for the first time at the same moment.
				// The row is what was wanted and the row exists.
			}
		});
	}

	/** Marks the visit finished so the lane can admit the next truck. WP7 does this at the end of the process. */
	public void completeVisit(long executionId) {
		transactions.executeWithoutResult(status -> jdbc.update(
				"UPDATE execution SET status = 'COMPLETED', completed_at = SYSUTCDATETIME() "
						+ "WHERE execution_id = ?", executionId));
	}

	public int deadlockRetries() {
		return deadlockRetries.get();
	}

	/** Installs a fault for the gap between the insert and the engine start. Test-only. */
	public void injectFault(AdmissionFault fault) {
		this.fault = fault == null ? AdmissionFault.NONE : fault;
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
}
