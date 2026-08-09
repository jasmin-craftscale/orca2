package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * {@code audit_event} — core's FIRST traffic-growing table, which is exactly
 * the moment {@link WorldModelTables}'s Javadoc said the growth question would
 * have to be answered differently: one row per admin mutation, unbounded in
 * 1.x ({@code audit_history}), bounded here by the {@code audit} retention
 * class. That name is PROVISIONAL until the closed retention catalog is
 * reconciled; {@code RetentionClassRule} currently enforces at build time only
 * that every traffic-growing table <em>names</em> a class.
 */
public final class AuditTables {

	private AuditTables() {
	}

	/**
	 * One admin mutation: who, what, which entity, when — typed columns where
	 * 1.x had unsized strings.
	 *
	 * @param siteExternalId the scope dimension — the configured installation
	 *                       site, deliberately not FK'd: an audit write must
	 *                       not depend on configuration rows existing
	 */
	@PersistentTable(name = "audit_event", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("audit") // PROVISIONAL
	public record AuditEvent(
			long auditEventId,
			String siteExternalId,
			Instant occurredAt,
			String actor,
			String entityType,
			String entityExternalId,
			String action,
			String detail) {
	}
}
