package com.lynxis.orca.core.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;

import com.lynxis.orca.core.domain.IdentityTables;
import com.lynxis.orca.core.domain.IdentityTables.Role;
import com.lynxis.orca.core.domain.IdentityTables.RoleEntitlement;
import com.lynxis.orca.core.domain.IdentityTables.RoleSite;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedInsert;
import com.lynxis.orca.platform.scope.ScopedSelect;
import com.lynxis.orca.platform.scope.ScopedUpdate;

import lombok.RequiredArgsConstructor;

/**
 * {@code role}, {@code role_site} and {@code role_entitlement} through the seam.
 *
 * <p>Two scope dimensions meet here, deliberately: the role and its grants are
 * installation-realm rows, while {@code role_site} is site-dimensional — its
 * scope column is the site it maps the role to, so a mapping outside the
 * caller's site scope can be neither written nor read. See {@link IdentityTables}.
 *
 * <p>Replacing a grant set or a site scope retires the old rows and inserts the
 * new (§D3 — retired, never removed; the filtered unique indexes admit a fresh
 * grant after a revoked one). The retired rows are the grant history, bounded by
 * admin activity.
 */
@RequiredArgsConstructor
public class RoleRepository {

	private static final String REALM = IdentityTables.CONFIG_REALM_DIMENSION;

	private static final RowMapper<Role> ROLE_MAPPER = (rs, row) -> new Role(
			rs.getLong("role_id"),
			rs.getString("external_id"),
			rs.getString("config_realm"),
			rs.getString("name"),
			rs.getString("description"),
			Utc.instantAt(rs, "retired_at"),
			Utc.instantAt(rs, "created_at"));

	private final ScopeSeam seam;

	public List<Role> all() {
		return seam.select(ScopedSelect.from("role")
						.columns("role_id", "external_id", "config_realm", "name", "description",
								"retired_at", "created_at")
						.scopedBy(REALM)
						.orderBy("role_id"),
				ROLE_MAPPER);
	}

	public Optional<Role> byExternalId(String externalId) {
		return seam.select(ScopedSelect.from("role")
						.columns("role_id", "external_id", "config_realm", "name", "description",
								"retired_at", "created_at")
						.scopedBy(REALM)
						.where("external_id = ?", externalId),
				ROLE_MAPPER)
				.stream().findFirst();
	}

	/** Whether an ACTIVE role other than {@code excludingRoleId} carries the name. */
	public boolean nameInUse(String name, long excludingRoleId) {
		return seam.count(ScopedSelect.from("role")
				.scopedBy(REALM)
				.where("name = ? AND retired_at IS NULL AND role_id <> ?", name, excludingRoleId)) > 0;
	}

	public long insert(String externalId, String name, String description) {
		return seam.insertReturningKey(ScopedInsert.into("role")
						.scopedBy(REALM)
						.value("external_id", externalId)
						.value(REALM, IdentityTables.INSTALLATION_REALM)
						.value("name", name)
						.value("description", description),
				"role_id");
	}

	/** Applies the non-null assignments; {@code retired} is tri-state as on users. */
	public int update(String externalId, String name, String description, Boolean retired) {
		ScopedUpdate update = ScopedUpdate.table("role")
				.scopedBy(REALM)
				.where("external_id = ?", externalId);
		boolean touched = false;
		if (name != null) {
			update.set("name", name);
			touched = true;
		}
		if (description != null) {
			update.set("description", description);
			touched = true;
		}
		if (retired != null) {
			update.set("retired_at", retired ? Utc.now() : null);
			touched = true;
		}
		return touched ? seam.update(update) : 0;
	}

	// --- role_site — site-dimensional ---------------------------------------

	/** Every ACTIVE role↔site mapping, for assembling role views in one read. */
	public List<RoleSite> activeSiteMappings() {
		return seam.select(ScopedSelect.from("role_site")
						.columns("role_site_id", "role_id", "site_external_id", "retired_at", "created_at")
						.scopedBy("site_external_id")
						.where("retired_at IS NULL")
						.orderBy("role_site_id"),
				(rs, row) -> new RoleSite(
						rs.getLong("role_site_id"),
						rs.getLong("role_id"),
						rs.getString("site_external_id"),
						Utc.instantAt(rs, "retired_at"),
						Utc.instantAt(rs, "created_at")));
	}

	/** Retires every active mapping of the role, then grants the new set. */
	public void replaceSiteScope(long roleId, Collection<String> siteExternalIds) {
		seam.update(ScopedUpdate.table("role_site")
				.set("retired_at", Utc.now())
				.scopedBy("site_external_id")
				.where("role_id = ? AND retired_at IS NULL", roleId));
		for (String siteExternalId : siteExternalIds) {
			seam.insert(ScopedInsert.into("role_site")
					.scopedBy("site_external_id")
					.value("role_id", roleId)
					.value("site_external_id", siteExternalId));
		}
	}

	// --- role_entitlement — installation-realm ------------------------------

	/** Every ACTIVE grant, for assembling role views in one read. */
	public List<RoleEntitlement> activeGrants() {
		return seam.select(ScopedSelect.from("role_entitlement")
						.columns("role_entitlement_id", "role_id", "action_item_id", "config_realm",
								"retired_at", "created_at")
						.scopedBy(REALM)
						.where("retired_at IS NULL")
						.orderBy("role_entitlement_id"),
				(rs, row) -> new RoleEntitlement(
						rs.getLong("role_entitlement_id"),
						rs.getLong("role_id"),
						rs.getLong("action_item_id"),
						rs.getString("config_realm"),
						Utc.instantAt(rs, "retired_at"),
						Utc.instantAt(rs, "created_at")));
	}

	/** Retires every active grant of the role, then writes the new set. */
	public void replaceGrants(long roleId, Collection<Long> actionItemIds) {
		seam.update(ScopedUpdate.table("role_entitlement")
				.set("retired_at", Utc.now())
				.scopedBy(REALM)
				.where("role_id = ? AND retired_at IS NULL", roleId));
		for (long actionItemId : actionItemIds) {
			seam.insert(ScopedInsert.into("role_entitlement")
					.scopedBy(REALM)
					.value("role_id", roleId)
					.value("action_item_id", actionItemId)
					.value(REALM, IdentityTables.INSTALLATION_REALM));
		}
	}
}
