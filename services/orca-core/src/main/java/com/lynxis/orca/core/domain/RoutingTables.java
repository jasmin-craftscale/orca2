package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The screen-identity and team-routing tables created by
 * {@code V109__routing_screens.sql}. Both are {@link Growth#BOUNDED}: rows appear
 * when an administrator configures them, not when trucks move.
 */
public final class RoutingTables {

	private RoutingTables() {
	}

	/**
	 * The screen <em>identity</em>, deliberately not the renderer: a name, the
	 * process node it fronts, and the three SLA thresholds.
	 * What the operator sees on it is the builder-developer's, deferred.
	 *
	 * @param belowExpectedSec ·
	 * @param expectedSec      ·
	 * @param maxSec           per-screen SLA thresholds, seconds; NULL falls back
	 *                         to the global settings (there is no global
	 *                         below-expected — a 1.x fact carried deliberately)
	 */
	@PersistentTable(name = "screen", growth = Growth.BOUNDED)
	public record Screen(
			long screenId,
			String externalId,
			String siteExternalId,
			String name,
			String processDefinitionKey,
			String nodeReference,
			Integer belowExpectedSec,
			Integer expectedSec,
			Integer maxSec,
			Instant retiredAt,
			Instant createdAt) {
	}

	/**
	 * One routing rule: this team handles this screen on this lane, at this
	 * priority. The tuple is unique — 1.x never enforced it. Deleted rather than
	 * retired: pure configuration with no history riding on the row.
	 */
	@PersistentTable(name = "team_routing", growth = Growth.BOUNDED)
	public record TeamRouting(
			long teamRoutingId,
			String externalId,
			String siteExternalId,
			long teamId,
			long screenId,
			long laneId,
			Integer priority,
			Instant createdAt) {
	}
}
