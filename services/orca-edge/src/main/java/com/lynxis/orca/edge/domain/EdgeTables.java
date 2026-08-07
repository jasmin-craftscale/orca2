package com.lynxis.orca.edge.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * The tables {@code V101__ingest.sql} creates, declared where the build check can
 * read them.
 *
 * <p>{@link BufferedEvent} is the first {@link Growth#TRAFFIC_GROWING} table this
 * phase adds, and it is the reason {@code RetentionClassRule} exists: one row per
 * capture, forever, at every lane. §C3 sizes the buffer at ≥72 h of peak traffic —
 * which is a statement about how much has to fit, not about what removes it.
 */
public final class EdgeTables {

	private EdgeTables() {
	}

	/**
	 * One undelivered inbound event.
	 *
	 * <p>⚠️ The retention class name is <strong>PROVISIONAL</strong>, exactly as
	 * Phase 0's three are. §C2 invariant 4 closes the list with a database
	 * {@code CHECK} over 18 values, that list lives in the Data Dictionary which is
	 * not in this repository, and the register records that its two published
	 * copies disagree. The build check enforces that a class is <em>named</em> —
	 * which is what §B10 specifies — and naming one here is not the same as
	 * choosing the list.
	 *
	 * @param sequenceNo the per-lane FIFO position. Order is the guarantee: §B10
	 *                   asks for zero loss <em>and preserved order</em> on drain
	 * @param eventUuid  the producer's dedup key. A camera retrying after a lost
	 *                   acknowledgement sends the same one, and it must not become
	 *                   two events
	 * @param payload    the bytes that arrived, verbatim. For a plate read that is
	 *                   the vendor's {@code ZapPacket} XML — see
	 *                   {@code docs/lpr-wire-format-from-1x.md}
	 * @param attributes the normalised half, as JSON, decoded once at ingest.
	 *                   §C3 makes edge the hardware boundary, so the vendor's dialect
	 *                   stops here and this is what crosses to runtime. Null for a
	 *                   row buffered before {@code V102}
	 * @param status     {@code PENDING · DISPATCHED · ACKED · DEAD}
	 */
	@PersistentTable(name = "event_buffer", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("device_event") // PROVISIONAL — see above
	public record BufferedEvent(
			long sequenceNo,
			String eventUuid,
			String siteExternalId,
			String laneExternalId,
			String deviceExternalId,
			String eventType,
			String payload,
			String attributes,
			String status,
			int attempts,
			String lastError,
			Instant receivedAt,
			Instant dispatchedAt,
			Instant ackedAt) {

		public static final String PENDING = "PENDING";
		public static final String DISPATCHED = "DISPATCHED";
		public static final String ACKED = "ACKED";

		/** Delivered too many times without an acknowledgement. Visible, never discarded. */
		public static final String DEAD = "DEAD";
	}

	/**
	 * The latest known state of one device.
	 *
	 * <p>{@link Growth#BOUNDED}: one row per device, updated in place. A row appears
	 * when somebody registers a device, not when a truck arrives — which is the
	 * distinction the growth question is asking about, and the reason this one
	 * carries no retention class.
	 */
	@PersistentTable(name = "device_state", growth = Growth.BOUNDED)
	public record DeviceState(
			long deviceStateId,
			String siteExternalId,
			String laneExternalId,
			String deviceExternalId,
			String state,
			Instant observedAt) {
	}
}
