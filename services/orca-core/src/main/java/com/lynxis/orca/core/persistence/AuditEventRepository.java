package com.lynxis.orca.core.persistence;

import java.util.List;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.AuditTables.AuditEvent;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * {@code audit_event} through the seam — site-dimensional, append-only.
 * Nothing here updates or retires: an audit row is a fact, and retention (the
 * {@code audit} class) is the only thing that ever removes one.
 */
@RequiredArgsConstructor
public class AuditEventRepository {

	private static final String SCOPE = "site_external_id";

	private static final RowMapper<AuditEvent> MAPPER = (rs, row) -> new AuditEvent(
			rs.getLong("audit_event_id"),
			rs.getString("site_external_id"),
			Utc.instantAt(rs, "occurred_at"),
			rs.getString("actor"),
			rs.getString("entity_type"),
			rs.getString("entity_external_id"),
			rs.getString("action"),
			rs.getString("detail"));

	private final ScopeSeam seam;

	public void append(String siteExternalId, String actor, String entityType,
			String entityExternalId, String action, String detail) {
		seam.insert(ScopedInsert.into("audit_event")
				.scopedBy(SCOPE)
				.value(SCOPE, siteExternalId)
				.value("actor", actor)
				.value("entity_type", entityType)
				.value("entity_external_id", entityExternalId)
				.value("action", action)
				.value("detail", detail));
	}

	/**
	 * Latest first, bounded — an audit read is a page, never the table. This is
	 * the read {@code orderByDescending} was added to the seam for: on a
	 * traffic-growing table, "fetch all and reverse" is an unbounded read.
	 */
	public List<AuditEvent> latest(int limit) {
		return seam.select(ScopedSelect.from("audit_event")
						.columns("audit_event_id", "site_external_id", "occurred_at", "actor",
								"entity_type", "entity_external_id", "action", "detail")
						.scopedBy(SCOPE)
						.orderByDescending("audit_event_id")
						.limit(Math.max(limit, 1)),
				MAPPER);
	}
}
