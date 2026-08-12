package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The declaration metadata created by {@code V111__custom_entity_declarations.sql}.
 * Both tables are bounded by administrator-authored configuration, not gate traffic.
 */
public final class CustomEntityTables {

	private CustomEntityTables() {
	}

	/** One site-owned custom-entity declaration; it is not an applied database table. */
	@PersistentTable(name = "custom_entity", growth = Growth.BOUNDED)
	public record CustomEntity(
			long customEntityId,
			String externalId,
			String siteExternalId,
			String entityKind,
			String name,
			String tableIdentifier,
			long declarationVersion,
			Instant createdAt,
			Instant updatedAt) {
	}

	/** One stable, ordered field in a declaration. */
	@PersistentTable(name = "custom_entity_field", growth = Growth.BOUNDED)
	public record CustomEntityField(
			long customEntityFieldId,
			String externalId,
			long customEntityId,
			String siteExternalId,
			String identifier,
			String displayName,
			String fieldType,
			Integer maxLength,
			Integer numberPrecision,
			Integer numberScale,
			boolean nullable,
			boolean businessKey,
			int ordinal,
			Instant createdAt) {
	}
}
