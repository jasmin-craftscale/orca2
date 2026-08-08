package com.lynxis.orca.core.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.domain.SettingsTables.SettingDefinition;
import com.lynxis.orca.core.domain.SettingsTables.SettingHistory;
import com.lynxis.orca.core.domain.SettingsTables.SettingValue;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/** The settings registry, values and history through the seam — installation-realm. */
@RequiredArgsConstructor
public class SettingRepository {

	private static final String REALM = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final RowMapper<SettingDefinition> DEFINITION_MAPPER = (rs, row) -> new SettingDefinition(
			rs.getLong("setting_definition_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("setting_key"),
			rs.getString("value_type"),
			rs.getString("default_value"),
			rs.getString("description"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private static final RowMapper<SettingValue> VALUE_MAPPER = (rs, row) -> new SettingValue(
			rs.getLong("setting_value_id"),
			rs.getLong("setting_definition_id"),
			rs.getString("config_realm"),
			rs.getString("setting_value"),
			rs.getString("updated_by"),
			Utc.instantAt(rs, "updated_at"));

	private static final RowMapper<SettingHistory> HISTORY_MAPPER = (rs, row) -> new SettingHistory(
			rs.getLong("setting_history_id"),
			rs.getLong("setting_definition_id"),
			rs.getString("config_realm"),
			rs.getString("old_value"),
			rs.getString("new_value"),
			rs.getString("changed_by"),
			Utc.instantAt(rs, "changed_at"));

	private final ScopeSeam seam;

	public List<SettingDefinition> definitions() {
		return seam.select(ScopedSelect.from("setting_definition")
						.columns("setting_definition_id", "external_id", "config_realm", "setting_key",
								"value_type", "default_value", "description", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("setting_definition_id"),
				DEFINITION_MAPPER);
	}

	public Optional<SettingDefinition> definitionByKey(String settingKey) {
		return seam.select(ScopedSelect.from("setting_definition")
						.columns("setting_definition_id", "external_id", "config_realm", "setting_key",
								"value_type", "default_value", "description", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("setting_key = ? AND retired_at IS NULL", settingKey),
				DEFINITION_MAPPER)
				.stream().findFirst();
	}

	public List<SettingValue> values() {
		return seam.select(ScopedSelect.from("setting_value")
						.columns("setting_value_id", "setting_definition_id", "config_realm",
								"setting_value", "updated_by", "updated_at")
						.scopedBy(REALM)
						.orderBy("setting_value_id"),
				VALUE_MAPPER);
	}

	public Optional<SettingValue> valueOf(long settingDefinitionId) {
		return seam.select(ScopedSelect.from("setting_value")
						.columns("setting_value_id", "setting_definition_id", "config_realm",
								"setting_value", "updated_by", "updated_at")
						.scopedBy(REALM)
						.where("setting_definition_id = ?", settingDefinitionId),
				VALUE_MAPPER)
				.stream().findFirst();
	}

	/** Upserts the current value and APPENDS the change to history, in the caller's transaction. */
	public void write(long settingDefinitionId, String oldValue, String newValue, String changedBy) {
		int changed = seam.update(ScopedUpdate.table("setting_value")
				.set("setting_value", newValue)
				.set("updated_by", changedBy)
				.set("updated_at", Utc.now())
				.scopedBy(REALM)
				.where("setting_definition_id = ?", settingDefinitionId));
		if (changed == 0) {
			seam.insert(ScopedInsert.into("setting_value")
					.scopedBy(REALM)
					.value("setting_definition_id", settingDefinitionId)
					.value(REALM, IdentityTables.INSTALLATION_REALM)
					.value("setting_value", newValue)
					.value("updated_by", changedBy));
		}
		seam.insert(ScopedInsert.into("setting_history")
				.scopedBy(REALM)
				.value("setting_definition_id", settingDefinitionId)
				.value(REALM, IdentityTables.INSTALLATION_REALM)
				.value("old_value", oldValue)
				.value("new_value", newValue)
				.value("changed_by", changedBy));
	}

	public List<SettingHistory> historyOf(long settingDefinitionId) {
		return seam.select(ScopedSelect.from("setting_history")
						.columns("setting_history_id", "setting_definition_id", "config_realm",
								"old_value", "new_value", "changed_by", "changed_at")
						.scopedBy(REALM)
						.where("setting_definition_id = ?", settingDefinitionId)
						.orderBy("setting_history_id"),
				HISTORY_MAPPER);
	}
}
