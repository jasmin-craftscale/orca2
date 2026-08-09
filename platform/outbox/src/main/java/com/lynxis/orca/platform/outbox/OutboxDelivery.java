package com.lynxis.orca.platform.outbox;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * One fact's state for one registered consumer.
 *
 * <p>This is what makes "a row is deletable only when every registered consumer has
 * acknowledged it" a query rather than a hope. Retention joins these rows and
 * therefore cannot destroy a fact that a registered peer never received.
 *
 * @param acknowledged whether this consumer has taken it. An unacknowledged row
 *                     holds retention off, whether or not its consumer is running
 */
@PersistentTable(name = "outbox_delivery", growth = Growth.TRAFFIC_GROWING)
// PROVISIONAL — see the note on OutboxRecord. The closed class list is not in this
// repository and its two published copies disagree.
@RetentionClass("outbox_delivery")
public record OutboxDelivery(
		long publishSeq,
		String consumer,
		boolean acknowledged,
		int attempts,
		Instant acknowledgedAt) {
}
