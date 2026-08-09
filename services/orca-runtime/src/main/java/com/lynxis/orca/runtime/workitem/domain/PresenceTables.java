package com.lynxis.orca.runtime.workitem.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/** The table {@code V116__operator_presence.sql} creates. */
public final class PresenceTables {

	private PresenceTables() {
	}

	/**
	 * One presence transition. The open row ({@code endedAt == null}) IS the
	 * operator's current status — there is no status column on any user row to
	 * drift out of sync, and a filtered unique index holds "at most one open row
	 * per operator" as a database fact.
	 *
	 * <p>{@link Growth#TRAFFIC_GROWING}: one row per transition per operator per
	 * shift, forever — the unbounded growth the sheet flags on 1.x's table,
	 * bounded here by the retention class (PROVISIONAL, like every class until
	 * the list closes).
	 */
	@PersistentTable(name = "user_activity", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("presence") // PROVISIONAL
	public record UserActivity(
			long userActivityId,
			String siteExternalId,
			String userExternalId,
			String status,
			Instant startedAt,
			Instant endedAt) {

		public static final String IDLE = "IDLE";
		public static final String WORKING = "WORKING";
		public static final String DND = "DND";
		public static final String BREAK = "BREAK";
		public static final String OFFLINE = "OFFLINE";
		public static final String ACTIVE = "ACTIVE";
	}
}
