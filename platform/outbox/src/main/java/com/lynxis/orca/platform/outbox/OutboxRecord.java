package com.lynxis.orca.platform.outbox;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * One recorded fact.
 *
 * @param publishSeq  the monotonic sequence. A feed cursor advances over it
 * @param orderingKey facts are ordered per key, never globally (§D3). A consumer
 *                    that needs global order is using the wrong mechanism
 * @param eventType   what happened, in the owner's vocabulary
 * @param payload     the fact's body, as the owner serialised it. The platform
 *                    does not parse it, and could not: doing so would mean
 *                    {@code platform/} knowing what a visit is
 * @param createdAt   set by the DATABASE's clock, not the writing instance's
 */
@PersistentTable(name = "outbox", growth = Growth.TRAFFIC_GROWING)
// PROVISIONAL. §B10 says the retention-class list "is closed and enumerated" and
// §C2 closes it with an 18-value database CHECK — but that list lives in the Data
// Dictionary, which is not in this repository, and the open-questions register
// records that the two documents carrying it do not agree. The build check
// enforces that a class is NAMED, which is what §B10 specifies. The name itself
// must be reconciled against the closed list before this ships.
@RetentionClass("outbox")
public record OutboxRecord(
		long publishSeq,
		String orderingKey,
		String eventType,
		String payload,
		Instant createdAt) {
}
