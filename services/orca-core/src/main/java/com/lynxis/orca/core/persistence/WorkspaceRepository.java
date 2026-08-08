package com.lynxis.orca.core.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.domain.WorkspaceTables.GridDefinition;
import com.lynxis.orca.core.domain.WorkspaceTables.SavedFilter;
import com.lynxis.orca.core.domain.WorkspaceTables.UserGridColumnPreference;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** The grid catalog and one user's workspace (column prefs, saved filters) — installation-realm. */
@RequiredArgsConstructor
public class WorkspaceRepository {

	private static final String REALM = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final RowMapper<GridDefinition> GRID_MAPPER = (rs, row) -> new GridDefinition(
			rs.getLong("grid_definition_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("code"),
			rs.getString("title"),
			rs.getString("default_columns"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<UserGridColumnPreference> PREFERENCE_MAPPER = (rs, row) ->
			new UserGridColumnPreference(
					rs.getLong("user_grid_column_preference_id"),
					rs.getLong("user_id"),
					rs.getLong("grid_definition_id"),
					rs.getString("config_realm"),
					rs.getString("column_code"),
					rs.getInt("display_order"),
					(Integer) rs.getObject("width_px"),
					rs.getBoolean("is_visible"),
					Utc.instantAt(rs, "retired_at"),
					Utc.instantAt(rs, "created_at"));

	private static final RowMapper<SavedFilter> FILTER_MAPPER = (rs, row) -> new SavedFilter(
			rs.getLong("saved_filter_id"),
			rs.getString("external_id"),
			rs.getLong("user_id"),
			rs.getLong("grid_definition_id"),
			rs.getString("config_realm"),
			rs.getString("name"),
			rs.getString("filter_json"),
			rs.getInt("filter_schema_version"),
			rs.getBoolean("is_default"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<GridDefinition> grids() {
		return seam.select(ScopedSelect.from("grid_definition")
						.columns("grid_definition_id", "external_id", "config_realm", "code", "title",
								"default_columns", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("grid_definition_id"),
				GRID_MAPPER);
	}

	public List<UserGridColumnPreference> preferencesOf(long userId) {
		return seam.select(ScopedSelect.from("user_grid_column_preference")
						.columns("user_grid_column_preference_id", "user_id", "grid_definition_id",
								"config_realm", "column_code", "display_order", "width_px",
								"is_visible", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("user_id = ? AND retired_at IS NULL", userId)
						.orderBy("user_grid_column_preference_id"),
				PREFERENCE_MAPPER);
	}

	/** Retires the user's preferences for one grid and writes the new set. */
	public void replacePreferences(long userId, long gridDefinitionId,
			Collection<UserGridColumnPreference> preferences) {
		seam.update(ScopedUpdate.table("user_grid_column_preference")
				.set("retired_at", Utc.now())
				.scopedBy(REALM)
				.where("user_id = ? AND grid_definition_id = ? AND retired_at IS NULL",
						userId, gridDefinitionId));
		for (UserGridColumnPreference preference : preferences) {
			seam.insert(ScopedInsert.into("user_grid_column_preference")
					.scopedBy(REALM)
					.value("user_id", userId)
					.value("grid_definition_id", gridDefinitionId)
					.value(REALM, IdentityTables.INSTALLATION_REALM)
					.value("column_code", preference.columnCode())
					.value("display_order", preference.displayOrder())
					.value("width_px", preference.widthPx())
					.value("is_visible", preference.isVisible()));
		}
	}

	public List<SavedFilter> filtersOf(long userId) {
		return seam.select(ScopedSelect.from("saved_filter")
						.columns("saved_filter_id", "external_id", "user_id", "grid_definition_id",
								"config_realm", "name", "filter_json", "filter_schema_version",
								"is_default", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("user_id = ? AND retired_at IS NULL", userId)
						.orderBy("saved_filter_id"),
				FILTER_MAPPER);
	}

	public Optional<SavedFilter> filterByExternalId(String externalId) {
		return seam.select(ScopedSelect.from("saved_filter")
						.columns("saved_filter_id", "external_id", "user_id", "grid_definition_id",
								"config_realm", "name", "filter_json", "filter_schema_version",
								"is_default", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("external_id = ?", externalId),
				FILTER_MAPPER)
				.stream().findFirst();
	}

	public void insertFilter(String externalId, long userId, long gridDefinitionId, String name,
			String filterJson, int schemaVersion, boolean isDefault) {
		seam.insert(ScopedInsert.into("saved_filter")
				.scopedBy(REALM)
				.value("external_id", externalId)
				.value("user_id", userId)
				.value("grid_definition_id", gridDefinitionId)
				.value(REALM, IdentityTables.INSTALLATION_REALM)
				.value("name", name)
				.value("filter_json", filterJson)
				.value("filter_schema_version", schemaVersion)
				.value("is_default", isDefault));
	}

	/** Clears the default flag on the user's other filters for the grid — one default at most. */
	public void clearDefault(long userId, long gridDefinitionId) {
		seam.update(ScopedUpdate.table("saved_filter")
				.set("is_default", false)
				.scopedBy(REALM)
				.where("user_id = ? AND grid_definition_id = ? AND is_default = 1 AND retired_at IS NULL",
						userId, gridDefinitionId));
	}

	public int retireFilter(String externalId, long userId) {
		return seam.update(ScopedUpdate.table("saved_filter")
				.set("retired_at", Utc.now())
				.scopedBy(REALM)
				.where("external_id = ? AND user_id = ? AND retired_at IS NULL", externalId, userId));
	}
}
