package com.lynxis.orca.core.persistence;

import java.util.List;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.domain.IdentityTables.EntitlementActionItem;
import com.lynxis.orca.core.domain.IdentityTables.EntitlementApplication;
import com.lynxis.orca.core.domain.IdentityTables.EntitlementModule;
import com.lynxis.orca.core.domain.IdentityTables.EntitlementSubModule;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * Read-only access to the seeded entitlement catalog (V104).
 *
 * <p>The seam has no joins, deliberately — so the tree is read level by level
 * and assembled in memory. That is the right trade here: the whole GATE tree is
 * 208 rows, bounded by migration, and four bounded reads beat publishing a
 * join-shaped view for a table set only this service reads.
 *
 * <p>Writes happen only in migrations: the catalog is product-owned reference
 * data (translation rule 7), so this repository deliberately has no insert.
 */
@RequiredArgsConstructor
public class EntitlementCatalogRepository {

	private static final String REALM = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final RowMapper<EntitlementApplication> APPLICATION_MAPPER = (rs, row) ->
			new EntitlementApplication(
					rs.getLong("application_id"),
					rs.getString("external_id"),
					rs.getString("config_realm"),
					rs.getString("code"),
					rs.getString("name"),
					Utc.instantAt(rs, "retired_at"),
					Utc.instantAt(rs, "created_at"));

	private static final RowMapper<EntitlementModule> MODULE_MAPPER = (rs, row) ->
			new EntitlementModule(
					rs.getLong("module_id"),
					rs.getLong("application_id"),
					rs.getString("external_id"),
					rs.getString("config_realm"),
					rs.getString("code"),
					rs.getString("name"),
					Utc.instantAt(rs, "retired_at"),
					Utc.instantAt(rs, "created_at"));

	private static final RowMapper<EntitlementSubModule> SUB_MODULE_MAPPER = (rs, row) ->
			new EntitlementSubModule(
					rs.getLong("sub_module_id"),
					rs.getLong("module_id"),
					rs.getString("external_id"),
					rs.getString("config_realm"),
					rs.getString("code"),
					rs.getString("name"),
					Utc.instantAt(rs, "retired_at"),
					Utc.instantAt(rs, "created_at"));

	private static final RowMapper<EntitlementActionItem> ACTION_ITEM_MAPPER = (rs, row) ->
			new EntitlementActionItem(
					rs.getLong("action_item_id"),
					rs.getLong("sub_module_id"),
					rs.getString("external_id"),
					rs.getString("config_realm"),
					rs.getString("code"),
					rs.getString("name"),
					rs.getString("licence_route"),
					Utc.instantAt(rs, "retired_at"),
					Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	/** Active applications, in seed order — which carries the 1.x console's menu order. */
	public List<EntitlementApplication> applications() {
		return seam.select(ScopedSelect.from("entitlement_application")
						.columns("application_id", "external_id", "config_realm", "code", "name",
								"retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("application_id"),
				APPLICATION_MAPPER);
	}

	public List<EntitlementModule> modules() {
		return seam.select(ScopedSelect.from("entitlement_module")
						.columns("module_id", "application_id", "external_id", "config_realm", "code",
								"name", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("module_id"),
				MODULE_MAPPER);
	}

	public List<EntitlementSubModule> subModules() {
		return seam.select(ScopedSelect.from("entitlement_sub_module")
						.columns("sub_module_id", "module_id", "external_id", "config_realm", "code",
								"name", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("sub_module_id"),
				SUB_MODULE_MAPPER);
	}

	public List<EntitlementActionItem> actionItems() {
		return seam.select(ScopedSelect.from("entitlement_action_item")
						.columns("action_item_id", "sub_module_id", "external_id", "config_realm",
								"code", "name", "licence_route", "retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("action_item_id"),
				ACTION_ITEM_MAPPER);
	}
}
