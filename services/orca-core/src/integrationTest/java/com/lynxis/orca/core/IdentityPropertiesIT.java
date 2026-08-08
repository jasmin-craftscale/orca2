package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.core.api.RoleAdminController;
import com.lynxis.orca.core.api.UserAdminController;
import com.lynxis.orca.core.api.generated.model.CreateRoleRequest;
import com.lynxis.orca.core.api.generated.model.CreateUserRequest;
import com.lynxis.orca.core.api.generated.model.RoleSummary;
import com.lynxis.orca.core.api.generated.model.UpdateRoleRequest;
import com.lynxis.orca.core.api.generated.model.UpdateUserRequest;
import com.lynxis.orca.core.api.generated.model.UserSummary;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.RoleAdminService;
import com.lynxis.orca.core.domain.UserAdminService;
import com.lynxis.orca.core.persistence.EntitlementCatalogRepository;
import com.lynxis.orca.core.persistence.RoleRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.web.ApiException;

/**
 * <strong>WP1 · identity: what the schema refuses, and what the API round-trips.</strong>
 *
 * <p>Sheet rules 2–3 as executable facts: every uniqueness rule is a database
 * constraint proven by a deliberate duplicate, not a SELECT-then-INSERT
 * convention (which is exactly the 1.x defect this phase does not port). Plus
 * the plan's done-when: a role's resolved entitlements round-trip through the
 * API, and the two-dimension scope design denies by default.
 */
class IdentityPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String SITE = "SITE-IT";

	private static DataSource owner;

	private JdbcTemplate core;
	private UserAdminController userApi;
	private RoleAdminController roleApi;

	@BeforeAll
	static void migrate() {
		PlatformDatabase.consumerLogin("orca_runtime");
		PlatformDatabase.consumerLogin("orca_edge");
		owner = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease");
	}

	@BeforeEach
	void freshWorldAndBeans() {
		core = new JdbcTemplate(owner);
		core.execute("DELETE FROM role_entitlement");
		core.execute("DELETE FROM role_site");
		core.execute("DELETE FROM user_account");
		core.execute("DELETE FROM role");
		core.execute("DELETE FROM device");
		core.execute("DELETE FROM lane");
		core.execute("DELETE FROM area");
		core.execute("DELETE FROM site");
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES (?, 'IT', 'IT Terminal', 1)", SITE);

		ScopeSeam seam = new JdbcScopeSeam(core);
		UserAccountRepository users = new UserAccountRepository(seam);
		RoleRepository roles = new RoleRepository(seam);
		EntitlementCatalogRepository catalog = new EntitlementCatalogRepository(seam);
		SiteDirectoryRepository sites = new SiteDirectoryRepository(seam);
		userApi = new UserAdminController(new UserAdminService(users, roles), SITE);
		roleApi = new RoleAdminController(new RoleAdminService(roles, catalog, sites, users), SITE);
	}

	// ------------------------------------------------------------------------
	// What the database refuses — sheet rules 2 and 3, as constraints.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("every identity table refuses a duplicate external id — 1.x had exactly one unique tag; 2.0 has one per table")
	void externalIdsAreUnique() {
		long roleId = insertRole("rol-dup", "First");

		assertThatThrownBy(() -> core.update(
				"INSERT INTO role (external_id, name) VALUES ('rol-dup', 'Second')"))
				.isInstanceOf(DuplicateKeyException.class);

		core.update("INSERT INTO user_account (external_id, display_name, email, role_id) "
				+ "VALUES ('usr-dup', 'A', 'a@example.test', " + roleId + ")");
		assertThatThrownBy(() -> core.update(
				"INSERT INTO user_account (external_id, display_name, email, role_id) "
						+ "VALUES ('usr-dup', 'B', 'b@example.test', " + roleId + ")"))
				.as("the retrofit 1.x migration silently degraded to non-unique on duplicates; "
						+ "this constraint is what makes that impossible here")
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@DisplayName("an active role name is unique per installation; a retired role's name is reusable")
	void roleNamesAreUniqueAmongActiveRoles() {
		insertRole("rol-1", "Gate Clerk");

		assertThatThrownBy(() -> core.update(
				"INSERT INTO role (external_id, name) VALUES ('rol-2', 'Gate Clerk')"))
				.isInstanceOf(DuplicateKeyException.class);

		core.update("UPDATE role SET retired_at = SYSUTCDATETIME() WHERE external_id = 'rol-1'");
		core.update("INSERT INTO role (external_id, name) VALUES ('rol-3', 'Gate Clerk')");

		assertThat(core.queryForObject(
				"SELECT COUNT(*) FROM role WHERE name = 'Gate Clerk' AND retired_at IS NULL", Long.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("the grant pair (role, action item) and the scoping pair (site, role) are database constraints, not conventions")
	void grantAndScopePairsAreUnique() {
		long roleId = insertRole("rol-p", "Pairs");
		Long itemId = core.queryForObject(
				"SELECT action_item_id FROM entitlement_action_item WHERE code = 'GATE.ADMIN.ROLE_MANAGEMENT.ADD_ROLE'",
				Long.class);

		core.update("INSERT INTO role_entitlement (role_id, action_item_id) VALUES (?, ?)", roleId, itemId);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO role_entitlement (role_id, action_item_id) VALUES (?, ?)", roleId, itemId))
				.as("1.x allowed duplicate grants; the racy SELECT-then-INSERT is not ported")
				.isInstanceOf(DuplicateKeyException.class);

		core.update("INSERT INTO role_site (role_id, site_external_id) VALUES (?, ?)", roleId, SITE);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO role_site (role_id, site_external_id) VALUES (?, ?)", roleId, SITE))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@DisplayName("a user cannot exist without a role, and a role_site row cannot name a site that does not exist")
	void foreignKeysHold() {
		assertThatThrownBy(() -> core.update(
				"INSERT INTO user_account (external_id, display_name, email, role_id) "
						+ "VALUES ('usr-orphan', 'X', 'x@example.test', 999999)"))
				.as("exactly one role per user is structural: the column is NOT NULL and enforced")
				.isInstanceOf(DataIntegrityViolationException.class);

		long roleId = insertRole("rol-fk", "FK");
		assertThatThrownBy(() -> core.update(
				"INSERT INTO role_site (role_id, site_external_id) VALUES (?, 'SITE-NOWHERE')", roleId))
				.as("the scope column is an FK to site(external_id) — a denormalized scope value cannot drift")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("one keycloak subject maps to at most one active user")
	void keycloakSubjectIsUniqueAmongActiveUsers() {
		long roleId = insertRole("rol-kc", "KC");
		core.update("INSERT INTO user_account (external_id, display_name, email, role_id, keycloak_subject) "
				+ "VALUES ('usr-kc1', 'A', 'a@example.test', ?, 'kc-sub-1')", roleId);

		assertThatThrownBy(() -> core.update(
				"INSERT INTO user_account (external_id, display_name, email, role_id, keycloak_subject) "
						+ "VALUES ('usr-kc2', 'B', 'b@example.test', ?, 'kc-sub-1')", roleId))
				.as("two users answering one token is an authorization hole, refused by the database")
				.isInstanceOf(DuplicateKeyException.class);
	}

	// ------------------------------------------------------------------------
	// The API round trip — the plan's done-when.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("a role's grants and site scope round-trip through the API, by stable code")
	void roleEntitlementsRoundTrip() {
		RoleSummary created = inScope(() -> roleApi.createRole(new CreateRoleRequest()
				.name("Gate Supervisor")
				.description("Runs the gate")
				.siteExternalIds(List.of(SITE))
				.entitlementCodes(List.of(
						"GATE.OPERATIONS.WORK_ITEM_QUEUE.TAKE_WORKITEM",
						"GATE.ADMIN.USER_MANAGEMENT.ADD_USER"))).getBody().getData());

		List<RoleSummary> listed = inScope(() -> roleApi.listRoles().getBody().getData());
		assertThat(listed).hasSize(1);
		RoleSummary role = listed.getFirst();
		assertThat(role.getExternalId()).isEqualTo(created.getExternalId());
		assertThat(role.getEntitlementCodes())
				.as("granted by code, resolved by code — names never carry authority (rule 7)")
				.containsExactlyInAnyOrder(
						"GATE.OPERATIONS.WORK_ITEM_QUEUE.TAKE_WORKITEM",
						"GATE.ADMIN.USER_MANAGEMENT.ADD_USER");
		assertThat(role.getSiteExternalIds()).containsExactly(SITE);

		// Replacement is declarative: the new set is the whole truth.
		RoleSummary updated = inScope(() -> roleApi.updateRole(created.getExternalId(),
				new UpdateRoleRequest().entitlementCodes(List.of(
						"GATE.INSIGHTS.LANE_MONITORS.VIEW_LANE_MONITORS"))).getBody().getData());
		assertThat(updated.getEntitlementCodes())
				.containsExactly("GATE.INSIGHTS.LANE_MONITORS.VIEW_LANE_MONITORS");
	}

	@Test
	@DisplayName("granting an unknown entitlement code or an unknown site is refused with the typed error")
	void unknownGrantTargetsAreRefused() {
		assertThatThrownBy(() -> inScope(() -> roleApi.createRole(new CreateRoleRequest()
				.name("Typo").entitlementCodes(List.of("GATE.NOPE.NOPE.NOPE")))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("ENTITLEMENT_UNKNOWN"));

		assertThatThrownBy(() -> inScope(() -> roleApi.createRole(new CreateRoleRequest()
				.name("Elsewhere").siteExternalIds(List.of("SITE-NOWHERE")))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("SITE_UNKNOWN"));
	}

	@Test
	@DisplayName("a user is created with exactly one role, patched, retired — and a held role cannot be retired")
	void userLifecycleAndRoleRetirementGuard() {
		RoleSummary role = inScope(() -> roleApi.createRole(
				new CreateRoleRequest().name("Clerk")).getBody().getData());

		UserSummary created = inScope(() -> userApi.createUser(new CreateUserRequest()
				.displayName("Devon Clerk")
				.email("devon@example.test")
				.roleExternalId(role.getExternalId())).getBody().getData());
		assertThat(created.getExternalId()).startsWith("usr-");
		assertThat(created.getRoleExternalId()).isEqualTo(role.getExternalId());

		// The role is held by an active user: retiring it must be refused.
		assertThatThrownBy(() -> inScope(() -> roleApi.updateRole(role.getExternalId(),
				new UpdateRoleRequest().retired(true))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("ROLE_IN_USE"));

		// Retire the user, and the role can follow.
		UserSummary retired = inScope(() -> userApi.updateUser(created.getExternalId(),
				new UpdateUserRequest().retired(true)).getBody().getData());
		assertThat(retired.getRetired()).isTrue();

		RoleSummary retiredRole = inScope(() -> roleApi.updateRole(role.getExternalId(),
				new UpdateRoleRequest().retired(true)).getBody().getData());
		assertThat(retiredRole.getRetired()).isTrue();
	}

	@Test
	@DisplayName("creating a user against an unknown role is 422 ROLE_UNKNOWN, not a stack trace")
	void unknownRoleIsATypedRefusal() {
		assertThatThrownBy(() -> inScope(() -> userApi.createUser(new CreateUserRequest()
				.displayName("Nobody").email("n@example.test").roleExternalId("rol-none"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("ROLE_UNKNOWN"));
	}

	// ------------------------------------------------------------------------
	// The scope design: deny by default, in both dimensions.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("beneath the request boundary, no scope means no rows — DENY is empty, never everything")
	void noScopeMeansNoRows() {
		insertRole("rol-deny", "Hidden");

		// The controllers ESTABLISH scope (they are the request boundary, and it
		// comes from configuration) — so the deny property lives one layer down:
		// a repository reached with no ambient scope reads nothing and writes
		// nothing. This is the path a mis-wired background job would take.
		RoleRepository unscoped = new RoleRepository(new JdbcScopeSeam(core));
		assertThat(unscoped.all())
				.as("the silent-bypass failure mode: a path that never set a scope must read nothing")
				.isEmpty();
		assertThatThrownBy(() -> unscoped.insert("rol-sneak", "Sneak", null))
				.as("an out-of-scope write throws where an out-of-scope read is empty (phase-1 decision 8)")
				.isInstanceOf(com.lynxis.orca.platform.scope.ScopeViolationException.class);
	}

	@Test
	@DisplayName("a scope naming another site reads no installation-realm rows and no foreign role_site mappings")
	void aForeignSiteScopeSeesNothing() {
		long roleId = insertRole("rol-fs", "Scoped");
		core.update("INSERT INTO role_site (role_id, site_external_id) VALUES (?, ?)", roleId, SITE);

		RoleRepository repository = new RoleRepository(new JdbcScopeSeam(core));
		Scope elsewhere = Scope.builder()
				.permit("site_external_id", Set.of("SITE-ELSEWHERE"))
				.build();
		ScopeContext.runIn(elsewhere, () -> {
			assertThat(repository.all())
					.as("a scope with no realm dimension reads no realm rows — permitting a site does "
							+ "not imply permission to the installation's configuration")
					.isEmpty();
			assertThat(repository.activeSiteMappings())
					.as("and this installation's mappings are invisible to a scope for another site")
					.isEmpty();
		});
	}

	// ------------------------------------------------------------------------

	private long insertRole(String externalId, String name) {
		core.update("INSERT INTO role (external_id, name) VALUES (?, ?)", externalId, name);
		return core.queryForObject("SELECT role_id FROM role WHERE external_id = ?", Long.class, externalId);
	}

	private <T> T inScope(Callable<T> work) {
		return ScopeContext.callIn(CoreScopes.installation(SITE), work);
	}
}
