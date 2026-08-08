package com.lynxis.orca.core.domain;

import java.time.Instant;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;

/**
 * The seven tables {@code V103__identity.sql} creates, declared where the build
 * check can read them — the same contract {@link WorldModelTables} documents:
 * declarations, not a mapping layer, mirroring the migration column for column,
 * and deliberately not {@code @Entity}.
 *
 * <p>All seven are {@link Growth#BOUNDED}: a row appears when an administrator
 * creates a user, defines a role or grants an entitlement — never when a truck
 * arrives. The catalog is seeded reference data (V104) and grows only by
 * migration.
 *
 * <p><strong>The two scope columns, stated once.</strong> {@code role_site}
 * carries {@code site_external_id} — site-dimensional, scope-leading index,
 * FK to {@code site(external_id)}. Everything else here carries
 * {@code config_realm}, constant {@code 'INSTALLATION'}: the seam has no
 * unscoped read, and these rows belong to the installation as a whole rather
 * than to a site. Reads of them declare the realm dimension deliberately, and
 * an unscoped path still reads nothing (DENY).
 */
public final class IdentityTables {

	/** The one value {@code config_realm} may hold, and the seam dimension it feeds. */
	public static final String CONFIG_REALM_DIMENSION = "config_realm";

	/** Rows in this realm belong to the installation as a whole. */
	public static final String INSTALLATION_REALM = "INSTALLATION";

	private IdentityTables() {
	}

	/** A customer-defined role. Name unique per installation among active roles. */
	@PersistentTable(name = "role", growth = Growth.BOUNDED)
	public record Role(
			long roleId,
			String externalId,
			String configRealm,
			String name,
			String description,
			Instant retiredAt,
			Instant createdAt) {
	}

	/**
	 * A platform user: profile and claim mapping only. Credentials are Keycloak's
	 * (§B6, sheet rule 9); {@code keycloakSubject} is how a token resolves to this
	 * row. Exactly one role per user, held structurally by the single NOT NULL
	 * {@code roleId}.
	 */
	@PersistentTable(name = "user_account", growth = Growth.BOUNDED)
	public record UserAccount(
			long userId,
			String externalId,
			String configRealm,
			String keycloakSubject,
			String firstName,
			String middleName,
			String lastName,
			String displayName,
			String email,
			String profileImageUrl,
			String languageCode,
			Instant privacyAcceptedAt,
			Instant termsAcceptedAt,
			long roleId,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Role↔site scoping — the real tenant-scoping relation (1.x user_site_mappings is dead). */
	@PersistentTable(name = "role_site", growth = Growth.BOUNDED)
	public record RoleSite(
			long roleSiteId,
			long roleId,
			String siteExternalId,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Catalog level 1 — the application (GATE; the PWA tree is the portal's, deferred). */
	@PersistentTable(name = "entitlement_application", growth = Growth.BOUNDED)
	public record EntitlementApplication(
			long applicationId,
			String externalId,
			String configRealm,
			String code,
			String name,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Catalog level 2. */
	@PersistentTable(name = "entitlement_module", growth = Growth.BOUNDED)
	public record EntitlementModule(
			long moduleId,
			long applicationId,
			String externalId,
			String configRealm,
			String code,
			String name,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** Catalog level 3. */
	@PersistentTable(name = "entitlement_sub_module", growth = Growth.BOUNDED)
	public record EntitlementSubModule(
			long subModuleId,
			long moduleId,
			String externalId,
			String configRealm,
			String code,
			String name,
			Instant retiredAt,
			Instant createdAt) {
	}

	/**
	 * Catalog level 4 — the grantable leaf.
	 *
	 * @param licenceRoute the 1.x licence-gate string, verbatim and deliberately
	 *                     NOT unique across the tree ({@code ExportExcel},
	 *                     {@code DeleteRecord} recur) — licence filtering by route
	 *                     is coarser than the catalog, and that fact is the
	 *                     licensing phase's to deal with, recorded rather than
	 *                     smoothed over
	 */
	@PersistentTable(name = "entitlement_action_item", growth = Growth.BOUNDED)
	public record EntitlementActionItem(
			long actionItemId,
			long subModuleId,
			String externalId,
			String configRealm,
			String code,
			String name,
			String licenceRoute,
			Instant retiredAt,
			Instant createdAt) {
	}

	/** A grant: role → catalog leaf. Leaf FK only; the 1.x event_data graft did not port. */
	@PersistentTable(name = "role_entitlement", growth = Growth.BOUNDED)
	public record RoleEntitlement(
			long roleEntitlementId,
			long roleId,
			long actionItemId,
			String configRealm,
			Instant retiredAt,
			Instant createdAt) {
	}
}
