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

	/**
	 * Upserts the current value and APPENDS the change to history, in the
	 * caller's transaction, and returns the value that was replaced.
	 *
	 * <p>Concurrency is handled here, not hoped away (review finding, Phase 2
	 * addendum): the existing row is read under {@code UPDLOCK}
	 * ({@code lockMatchedRows}), so two writers serialize and each history row
	 * records the old value that was really replaced. The first-ever write has
	 * no row to lock — two concurrent first writes both insert, the loser hits
	 * {@code uq_setting_value_definition}, and instead of surfacing a 500 it
	 * re-reads (now locked, now present) and retries as the update it has
	 * become.
	 */
	public String write(long settingDefinitionId, String newValue, String changedBy) {
		String oldValue;
		Optional<SettingValue> current = lockedValueOf(settingDefinitionId);
		if (current.isPresent()) {
			oldValue = current.get().settingValue();
			updateValue(settingDefinitionId, newValue, changedBy);
		}
		else {
			try {
				seam.insert(ScopedInsert.into("setting_value")
						.scopedBy(REALM)
						.value("setting_definition_id", settingDefinitionId)
						.value(REALM, IdentityTables.INSTALLATION_REALM)
						.value("setting_value", newValue)
						.value("updated_by", changedBy));
				oldValue = null;
			}
			catch (org.springframework.dao.DuplicateKeyException lostTheFirstWriteRace) {
				oldValue = lockedValueOf(settingDefinitionId)
						.map(SettingValue::settingValue)
						.orElse(null);
				updateValue(settingDefinitionId, newValue, changedBy);
			}
		}
		seam.insert(ScopedInsert.into("setting_history")
				.scopedBy(REALM)
				.value("setting_definition_id", settingDefinitionId)
				.value(REALM, IdentityTables.INSTALLATION_REALM)
				.value("old_value", oldValue)
				.value("new_value", newValue)
				.value("changed_by", changedBy));
		return oldValue;
	}

	private Optional<SettingValue> lockedValueOf(long settingDefinitionId) {
		return seam.select(ScopedSelect.from("setting_value")
						.columns("setting_value_id", "setting_definition_id", "config_realm",
								"setting_value", "updated_by", "updated_at")
						.scopedBy(REALM)
						.where("setting_definition_id = ?", settingDefinitionId)
						.lockMatchedRows(),
				VALUE_MAPPER)
				.stream().findFirst();
	}

	private void updateValue(long settingDefinitionId, String newValue, String changedBy) {
		seam.update(ScopedUpdate.table("setting_value")
				.set("setting_value", newValue)
				.set("updated_by", changedBy)
				.set("updated_at", Utc.now())
				.scopedBy(REALM)
				.where("setting_definition_id = ?", settingDefinitionId));
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
