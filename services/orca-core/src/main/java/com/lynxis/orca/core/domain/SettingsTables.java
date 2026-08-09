package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;

/**
 * The settings tables of {@code V107__settings_workspace_audit.sql}: the registry
 * of known keys, current values and append-only history.
 * Declarations for the build check; never {@code @Entity}.
 */
public final class SettingsTables {

	private SettingsTables() {
	}

	/** The registry: a key the platform knows, its type, and its default. Seeded (rule 7). */
	@PersistentTable(name = "setting_definition", growth = Growth.BOUNDED)
	public record SettingDefinition(
			long settingDefinitionId,
			String externalId,
			String configRealm,
			String settingKey,
			String valueType,
			String defaultValue,
			String description,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** The current value of one key — one row per definition, updated in place. */
	@PersistentTable(name = "setting_value", growth = Growth.BOUNDED)
	public record SettingValue(
			long settingValueId,
			long settingDefinitionId,
			String configRealm,
			String settingValue,
			String updatedBy,
			Instant updatedAt) {
	}

	/**
	 * Every change, appended — grows with admin activity and never shrinks by
	 * itself, so it is TRAFFIC_GROWING and carries the {@code audit} retention
	 * class (PROVISIONAL, like every class name until the published lists are
	 * reconciled — phase-1 report).
	 */
	@PersistentTable(name = "setting_history", growth = Growth.TRAFFIC_GROWING)
	@RetentionClass("audit") // PROVISIONAL
	public record SettingHistory(
			long settingHistoryId,
			long settingDefinitionId,
			String configRealm,
			String oldValue,
			String newValue,
			String changedBy,
			Instant changedAt) {
	}
}
