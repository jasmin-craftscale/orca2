package com.lynxis.orca.platform.lease;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * A held lease, and the token that proves it was held.
 *
 * @param service    the owning service, half of the natural key
 * @param leaseName  the other half. Per-client, per-site-pair and per-lane scope
 *                   <em>rides in this string</em> — {@code edge.ingest:lane:42} —
 *                   which is how one mechanism covers a retention job, a feed
 *                   reader and a per-lane election alike (§C2)
 * @param holderId   the instance identity that holds it
 * @param fenceToken increases every time the lease changes hands, and is presented
 *                   with every write the lease protects
 * @param expiresAt  by the DATABASE's clock. Do not compare it to a local one
 */
// BOUNDED, and therefore carries no retention class — which §C2 states explicitly
// among the things this table deliberately lacks. One row per coordination point,
// updated in place: adding a row means somebody configured something, not that
// traffic arrived.
@PersistentTable(name = "service_lease", growth = Growth.BOUNDED)
public record Lease(
		String service,
		String leaseName,
		String holderId,
		long fenceToken,
		Instant expiresAt) {

	/**
	 * Whether this lease looks expired against the given instant.
	 *
	 * <p><strong>Advisory only.</strong> Nothing may act on this: expiry is decided
	 * by the database, server-side, inside the guarded update, precisely so that
	 * two hosts with disagreeing clocks cannot disagree about who holds the lease.
	 * This exists for logging and for a UI, and using it as a guard would
	 * reintroduce the check-then-act race the whole table exists to remove.
	 */
	public boolean looksExpired(Instant now) {
		return expiresAt != null && !expiresAt.isAfter(now);
	}
}
