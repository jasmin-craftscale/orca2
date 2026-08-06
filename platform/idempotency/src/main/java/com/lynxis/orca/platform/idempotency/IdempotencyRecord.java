package com.lynxis.orca.platform.idempotency;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * One recorded key and what it produced.
 *
 * <p>One row per command, per applied fact, per retried request — so it grows with
 * traffic and is purged on a window. The window is not stated here: it is a
 * retention setting, and the closed retention-class list lives in the Data
 * Dictionary rather than in this repository.
 */
@PersistentTable(name = "idempotency_record", growth = Growth.TRAFFIC_GROWING)
// PROVISIONAL — see the note on OutboxRecord. The closed class list is not in this
// repository and its two published copies disagree.
@RetentionClass("idempotency_record")
public record IdempotencyRecord(
		String idempotencyKey,
		String operation,
		String status,
		String outcome,
		Instant createdAt,
		Instant completedAt) {
}
