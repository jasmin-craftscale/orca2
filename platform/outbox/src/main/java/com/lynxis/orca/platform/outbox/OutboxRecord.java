package com.lynxis.orca.platform.outbox;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * One recorded fact.
 *
 * @param publishSeq  the monotonic sequence. A feed cursor advances over it
 * @param orderingKey facts are ordered per key, never globally. A consumer that
 *                    needs global order is using the wrong mechanism
 * @param eventType   what happened, in the owner's vocabulary
 * @param payload     the fact's body, as the owner serialised it. The platform
 *                    does not parse it, and could not: doing so would mean
 *                    {@code platform/} knowing what a visit is
 * @param createdAt   set by the DATABASE's clock, not the writing instance's
 */
@PersistentTable(name = "outbox", growth = Growth.TRAFFIC_GROWING)
// PROVISIONAL. The retention-class catalog is supposed to be a closed set of 18
// values enforced by a database CHECK, but the catalog lives in a Data Dictionary
// outside this repository and its two published copies disagree. RetentionClassRule
// still fails the build unless every traffic-growing table NAMES a class; it cannot
// validate membership in the unavailable catalog. "outbox" must be reconciled
// against that catalog before this ships.
@RetentionClass("outbox")
public record OutboxRecord(
		long publishSeq,
		String orderingKey,
		String eventType,
		String payload,
		Instant createdAt) {
}
