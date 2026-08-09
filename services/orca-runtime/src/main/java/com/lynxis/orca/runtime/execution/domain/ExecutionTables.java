package com.lynxis.orca.runtime.execution.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * The tables {@code V101__execution.sql} creates, declared where the build check
 * can read them.
 *
 * <p>Two of the three are {@link Growth#TRAFFIC_GROWING}: one row per truck,
 * forever, at every lane. That is what {@code RetentionClassRule} is for, and it
 * is the reason a visit's business data lives here rather than in process
 * variables — the engine's history tables have no retention class and no policy
 * that reaches them (§C2).
 */
public final class ExecutionTables {

	private ExecutionTables() {
	}

	/**
	 * One lane's admission point.
	 *
	 * <p>{@link Growth#BOUNDED}: one row per lane, updated in place. It appears when
	 * a lane is first seen and is then reused by every truck that lane ever admits.
	 *
	 * <p>Keyed {@code (siteExternalId, laneId)} in that order, and the migration says
	 * at length why: every read through the seam leads with the scope predicate, and
	 * a key that does not lead with the scope column turns the lane lock into a
	 * site-wide one.
	 */
	@PersistentTable(name = "lane_session", growth = Growth.BOUNDED)
	public record LaneSession(
			String siteExternalId,
			long laneId,
			String laneExternalId,
			String boundPlate,
			Instant boundAt,
			Instant updatedAt) {
	}

	/**
	 * The visit.
	 *
	 * <p>⚠️ The retention class name is <strong>PROVISIONAL</strong>, as Phase 0's
	 * three and WP5's one are. §C2 invariant 4 closes the list with a database
	 * {@code CHECK} over 18 values; that list lives in the Data Dictionary, which is
	 * not in this repository, and the register records that its two published copies
	 * disagree. The build check enforces that a class is <em>named</em> — which is
	 * what §B10 specifies — and naming one here is not the same as choosing the list.
	 *
	 * @param status {@code ACTIVE} · {@code COMPLETED} · {@code MANUAL} ·
	 *               {@code FAILED}. Since Phase 3 a visit that needs a person no
	 *               longer <em>ends</em> — the process parks at the manual-input
	 *               wait state and the visit stays {@code ACTIVE} with a work item
	 *               open; {@code MANUAL} now marks the visit whose process ended at
	 *               the resolved end state after a person acted. {@code FAILED} is
	 *               lane reset's write (V115)
	 */
	@PersistentTable(name = "execution", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("visit") // PROVISIONAL — see above
	public record Execution(
			long executionId,
			String externalId,
			String siteExternalId,
			long laneId,
			Long parentExecutionId,
			String status,
			String plate,
			String processInstanceId,
			Instant startedAt,
			Instant completedAt) {

		public static final String ACTIVE = "ACTIVE";
		public static final String COMPLETED = "COMPLETED";

		/** Reached the resolved end state after manual handling. Distinguishable from a crash, deliberately. */
		public static final String MANUAL = "MANUAL";

		/** Lane reset's write (V115): the visit was aborted, its open work items failed with it. */
		public static final String FAILED = "FAILED";
	}

	/**
	 * One accepted device event, attached to the visit it belongs to.
	 *
	 * <p>⚠️ Retention class PROVISIONAL, and deliberately the same name edge's
	 * buffer uses: the same event is recorded on both sides of the link, and two
	 * different retention classes for one fact would let one side outlive the other.
	 */
	@PersistentTable(name = "execution_event", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("device_event") // PROVISIONAL — see above
	public record ExecutionEvent(
			long executionEventId,
			String eventUuid,
			String siteExternalId,
			long executionId,
			long laneId,
			String eventType,
			String deviceExternalId,
			String attributes,
			Instant receivedAt) {
	}
}
