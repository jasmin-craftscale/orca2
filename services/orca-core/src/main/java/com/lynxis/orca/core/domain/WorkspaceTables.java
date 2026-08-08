package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The workspace and branding tables of {@code V107} — the grid catalog,
 * per-user column preferences (relational, rule 11), saved filters (JSON with
 * a schema version, also rule 11 — decided, not defaulted), and per-site
 * colors/languages. All BOUNDED. Declarations only.
 */
public final class WorkspaceTables {

	private WorkspaceTables() {
	}

	/** The grid catalog — seeded with the grids whose backing features this phase built. */
	@PersistentTable(name = "grid_definition", growth = Growth.BOUNDED)
	public record GridDefinition(
			long gridDefinitionId,
			String externalId,
			String configRealm,
			String code,
			String title,
			String defaultColumns,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** One user's preference for one column of one grid — three scalars, so rows, not a blob. */
	@PersistentTable(name = "user_grid_column_preference", growth = Growth.BOUNDED)
	public record UserGridColumnPreference(
			long userGridColumnPreferenceId,
			long userId,
			long gridDefinitionId,
			String configRealm,
			String columnCode,
			int displayOrder,
			Integer widthPx,
			boolean isVisible,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** A saved filter — an expression tree, kept as versioned JSON deliberately. */
	@PersistentTable(name = "saved_filter", growth = Growth.BOUNDED)
	public record SavedFilter(
			long savedFilterId,
			String externalId,
			long userId,
			long gridDefinitionId,
			String configRealm,
			String name,
			String filterJson,
			int filterSchemaVersion,
			boolean isDefault,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Site branding color — normalized #RRGGBBAA. Created empty (rule 10). */
	@PersistentTable(name = "site_color", growth = Growth.BOUNDED)
	public record SiteColor(
			long siteColorId,
			String siteExternalId,
			String code,
			String hexValue,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Site localization entry. Created empty (rule 10). */
	@PersistentTable(name = "site_language", growth = Growth.BOUNDED)
	public record SiteLanguage(
			long siteLanguageId,
			String siteExternalId,
			String code,
			String name,
			String resourcePath,
			Instant retiredAt,
			Instant createdAt) {
	}
}
