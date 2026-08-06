package com.lynxis.orca.platform.outbox;

import java.time.Duration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.web.system.SystemContext;
import com.lynxis.orca.platform.web.system.SystemIdentity;

import lombok.RequiredArgsConstructor;

/**
 * Deletes facts, and only those <strong>every registered consumer has
 * acknowledged</strong>.
 *
 * <p>§B10: "Retention cannot destroy unreplicated data — a row is purge-eligible
 * only when every registered consumer has acknowledged <em>and</em> its window has
 * elapsed." Both halves are in the one statement below, and neither is optional:
 * age alone would let an outage become data loss, and acknowledgement alone would
 * let the table grow without bound.
 *
 * <p>The window is a parameter rather than a constant here. The retention-class
 * list is closed and enumerated in the Data Dictionary, which is not in this
 * repository, and the two documents that carry it do not agree — an open item.
 * This module therefore holds the mechanism and states no number.
 */
@RequiredArgsConstructor
public class OutboxRetention {

	/**
	 * Delivery rows first, because of the foreign key; and both restricted by the
	 * same {@code NOT EXISTS} so a fact and its deliveries can never be half-gone.
	 */
	private static final String DELETE_DELIVERIES = """
			DELETE d
			FROM outbox_delivery d
			JOIN outbox o ON o.publish_seq = d.publish_seq
			WHERE o.created_at < DATEADD(second, ?, SYSUTCDATETIME())
			  AND NOT EXISTS (
			        SELECT 1 FROM outbox_delivery pending
			        WHERE pending.publish_seq = o.publish_seq
			          AND pending.status <> 'ACKED')
			""";

	private static final String DELETE_FACTS = """
			DELETE o
			FROM outbox o
			WHERE o.created_at < DATEADD(second, ?, SYSUTCDATETIME())
			  AND NOT EXISTS (
			        SELECT 1 FROM outbox_delivery pending
			        WHERE pending.publish_seq = o.publish_seq
			          AND pending.status <> 'ACKED')
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final String serviceName;

	/**
	 * Purges facts older than {@code window} that every registered consumer has
	 * acknowledged.
	 *
	 * @return how many facts were deleted
	 */
	public int purgeAcknowledgedOlderThan(Duration window) {
		if (window.isNegative()) {
			throw new IllegalArgumentException("Retention window must not be negative");
		}
		long seconds = -window.toSeconds();
		return SystemContext.callAs(new SystemIdentity(serviceName, "outbox-retention"),
				() -> transactions.execute(status -> {
					jdbc.update(DELETE_DELIVERIES, seconds);
					return jdbc.update(DELETE_FACTS, seconds);
				}));
	}
}
