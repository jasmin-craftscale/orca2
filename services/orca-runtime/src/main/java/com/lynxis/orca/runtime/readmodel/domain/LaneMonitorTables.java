package com.lynxis.orca.runtime.readmodel.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/** The table {@code V138__lane_monitor_projection.sql} creates. */
public final class LaneMonitorTables {

	private LaneMonitorTables() {
	}

	/**
	 * One row in the operator lane board.
	 *
	 * <p>{@link Growth#BOUNDED}: one row per lane, updated in place as visits,
	 * work items and device observations move. The source records keep their own
	 * retention classes; this projection can be discarded and rebuilt.
	 */
	@PersistentTable(name = "lane_monitor", growth = Growth.BOUNDED)
	public record LaneMonitorRow(
			String siteExternalId,
			String siteCode,
			boolean sitePrimary,
			Long areaId,
			String areaExternalId,
			String areaCode,
			long laneId,
			String laneExternalId,
			String laneCode,
			String laneName,
			Integer lanePriority,
			boolean outOfService,
			String trafficStatus,
			String trafficColor,
			String visitExternalId,
			String plate,
			String queuedWorkItemExternalId,
			Instant queuedWorkItemQueuedAt,
			String queuedWorkItemAssignee,
			Instant queuedWorkItemSlaBreachedAt,
			String gateArm,
			String redLamp,
			String orangeLamp,
			String greenLamp,
			String loopInputs,
			String lastDeviceEventUuid,
			String lastDeviceEventType,
			Instant lastDeviceObservedAt,
			Instant updatedAt) {

		public static final String CLEAR = "CLEAR";
		public static final String ACTIVE = "ACTIVE";
		public static final String COMPLETED = "COMPLETED";
		public static final String MANUAL = "MANUAL";
		public static final String FAILED = "FAILED";

		public static final String NEUTRAL = "NEUTRAL";
		public static final String BLUE = "BLUE";
		public static final String GREEN = "GREEN";
		public static final String AMBER = "AMBER";
		public static final String RED = "RED";
	}
}
