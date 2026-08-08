package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.core.api.AuditEventController;
import com.lynxis.orca.core.api.SettingsController;
import com.lynxis.orca.core.api.SiteBrandingController;
import com.lynxis.orca.core.api.WorkspaceController;
import com.lynxis.orca.core.api.generated.model.AuditEventSummary;
import com.lynxis.orca.core.api.generated.model.ColumnPreference;
import com.lynxis.orca.core.api.generated.model.CreateFilterRequest;
import com.lynxis.orca.core.api.generated.model.GridPreferences;
import com.lynxis.orca.core.api.generated.model.SettingSummary;
import com.lynxis.orca.core.api.generated.model.SiteColorItem;
import com.lynxis.orca.core.api.generated.model.SiteLanguageItem;
import com.lynxis.orca.core.api.generated.model.UpdateSiteBrandingRequest;
import com.lynxis.orca.core.api.generated.model.WorkspaceGrid;
import com.lynxis.orca.core.api.generated.model.WriteSettingRequest;
import com.lynxis.orca.core.domain.AuditTrail;
import com.lynxis.orca.core.domain.CallerIdentity;
import com.lynxis.orca.core.domain.CoreScopes;
import com.lynxis.orca.core.domain.SettingsService;
import com.lynxis.orca.core.domain.SiteBrandingService;
import com.lynxis.orca.core.domain.WorkspaceService;
import com.lynxis.orca.core.persistence.AuditEventRepository;
import com.lynxis.orca.core.persistence.SettingRepository;
import com.lynxis.orca.core.persistence.SiteBrandingRepository;
import com.lynxis.orca.core.persistence.SiteDirectoryRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.core.persistence.WorkspaceRepository;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.web.ApiException;

/**
 * <strong>WP4 · settings, workspace, audit.</strong>
 *
 * <p>The plan's done-when, verbatim: a secret-shaped key is refused with the
 * typed error, settings history appends, and the audit trail records a config
 * mutation end to end.
 */
class SettingsWorkspaceAuditPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String SITE = "SITE-IT";
	private static final String SUBJECT = "kc-it-subject";

	private static DataSource owner;

	private JdbcTemplate core;
	private SettingsController settingsApi;
	private WorkspaceController workspaceApi;
	private SiteBrandingController brandingApi;
	private AuditEventController auditApi;
	private CallerIdentity caller;
	private String callerSubject;

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
		for (String table : List.of("audit_event", "setting_history", "setting_value", "saved_filter",
				"user_grid_column_preference", "site_color", "site_language", "team_member", "team",
				"role_entitlement", "role_site", "user_account", "role", "device", "lane", "area", "site")) {
			core.execute("DELETE FROM " + table);
		}
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES (?, 'IT', 'IT Terminal', 1)", SITE);
		core.update("INSERT INTO role (external_id, name) VALUES ('rol-it', 'Admin')");
		core.update("INSERT INTO user_account (external_id, display_name, email, role_id, keycloak_subject) "
				+ "SELECT 'usr-it-admin', 'IT Admin', 'admin@example.test', role_id, ? "
				+ "FROM role WHERE external_id = 'rol-it'", SUBJECT);

		callerSubject = SUBJECT;
		caller = () -> Optional.ofNullable(callerSubject);

		ScopeSeam seam = new JdbcScopeSeam(core);
		UserAccountRepository users = new UserAccountRepository(seam);
		AuditTrail audit = new AuditTrail(new AuditEventRepository(seam), users, caller, SITE);
		settingsApi = new SettingsController(
				new SettingsService(new SettingRepository(seam), audit), SITE);
		workspaceApi = new WorkspaceController(
				new WorkspaceService(new WorkspaceRepository(seam), users, caller), SITE);
		brandingApi = new SiteBrandingController(
				new SiteBrandingService(new SiteBrandingRepository(seam),
						new SiteDirectoryRepository(seam), audit), SITE);
		auditApi = new AuditEventController(new AuditEventRepository(seam), SITE);
	}

	// ------------------------------------------------------------------------
	// Settings — the done-when, item by item.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("SMTP_PASSWORD is refused with SETTING_SECRET_REJECTED — before the registry is even consulted")
	void aSecretShapedKeyIsRefused() {
		assertThatThrownBy(() -> inScope(() -> settingsApi.writeSetting("SMTP_PASSWORD",
				new WriteSettingRequest().value("hunter2"))))
				.as("verification item 8: the exact 1.x key rule 8 exists for")
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("SETTING_SECRET_REJECTED"));

		// The whole excluded family, not just the one key.
		for (String key : List.of("KEYCLOAK_CLIENT_SECRET", "VAPID_PRIVATE_KEY", "AZURE_STORAGE_KEY",
				"CLUSTER_PASSWORD", "PARTNER_API_TOKEN")) {
			assertThatThrownBy(() -> inScope(() -> settingsApi.writeSetting(key,
					new WriteSettingRequest().value("x"))))
					.isInstanceOfSatisfying(ApiException.class, refusal ->
							assertThat(refusal.getErrorCode().code()).isEqualTo("SETTING_SECRET_REJECTED"));
		}
		assertThat(core.queryForObject("SELECT COUNT(*) FROM setting_value", Long.class))
				.as("nothing secret-shaped ever reached the table")
				.isZero();
	}

	@Test
	@DisplayName("an unknown key is refused against the registry, and a value that fails the key's type is refused")
	void validatedWrites() {
		assertThatThrownBy(() -> inScope(() -> settingsApi.writeSetting("SOME_NOVEL_TUNABLE",
				new WriteSettingRequest().value("1"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("SETTING_UNKNOWN"));

		assertThatThrownBy(() -> inScope(() -> settingsApi.writeSetting("MAX_PROCESSING_TIME_SEC",
				new WriteSettingRequest().value("a while"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("settings history appends — every write, old value and new, attributed")
	void historyAppends() {
		inScope(() -> settingsApi.writeSetting("LOG_LEVEL", new WriteSettingRequest().value("DEBUG")));
		inScope(() -> settingsApi.writeSetting("LOG_LEVEL", new WriteSettingRequest().value("WARN")));

		List<java.util.Map<String, Object>> history = core.queryForList(
				"SELECT old_value, new_value, changed_by FROM setting_history sh "
						+ "JOIN setting_definition sd ON sd.setting_definition_id = sh.setting_definition_id "
						+ "WHERE sd.setting_key = 'LOG_LEVEL' ORDER BY sh.setting_history_id");
		assertThat(history).hasSize(2);
		assertThat(history.get(0).get("old_value")).isNull();
		assertThat(history.get(0).get("new_value")).isEqualTo("DEBUG");
		assertThat(history.get(1).get("old_value")).isEqualTo("DEBUG");
		assertThat(history.get(1).get("new_value")).isEqualTo("WARN");
		assertThat(history.get(1).get("changed_by"))
				.as("attributed to the platform user the token maps to")
				.isEqualTo("usr-it-admin");

		SettingSummary current = inScope(() -> settingsApi.listSettings().getBody().getData()).stream()
				.filter(setting -> setting.getKey().equals("LOG_LEVEL")).findFirst().orElseThrow();
		assertThat(current.getValue()).isEqualTo("WARN");
	}

	@Test
	@DisplayName("the audit trail records a config mutation end to end — write a setting, read the audit page")
	void auditRecordsAMutationEndToEnd() {
		inScope(() -> settingsApi.writeSetting("LOG_LEVEL", new WriteSettingRequest().value("DEBUG")));
		inScope(() -> settingsApi.writeSetting("LOG_LEVEL", new WriteSettingRequest().value("INFO")));

		List<AuditEventSummary> page = inScope(() ->
				auditApi.listAuditEvents(10).getBody().getData());
		assertThat(page).hasSize(2);
		AuditEventSummary newest = page.getFirst();
		assertThat(newest.getEntityType()).isEqualTo("SETTING");
		assertThat(newest.getEntityExternalId()).isEqualTo("LOG_LEVEL");
		assertThat(newest.getAction()).isEqualTo(AuditEventSummary.ActionEnum.UPDATED);
		assertThat(newest.getActor()).isEqualTo("usr-it-admin");
		assertThat(page.getLast().getAction())
				.as("newest first: the CREATE is the older entry")
				.isEqualTo(AuditEventSummary.ActionEnum.CREATED);
		assertThat(newest.getDetail())
				.as("the audit trail never carries the value — a setting can be sensitive without being a secret")
				.doesNotContain("DEBUG", "INFO");
	}

	// ------------------------------------------------------------------------
	// Workspace.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("grid preferences replace declaratively for the calling user, per grid")
	void gridPreferencesRoundTrip() {
		List<WorkspaceGrid> grids = inScope(() -> workspaceApi.myGrids().getBody().getData());
		assertThat(grids).extracting(WorkspaceGrid::getGridCode)
				.contains("USER_MANAGEMENT", "AUDIT_HISTORY");

		inScope(() -> workspaceApi.replaceMyGridPreferences(List.of(new GridPreferences()
				.gridCode("USER_MANAGEMENT")
				.columns(List.of(
						new ColumnPreference().columnCode("displayName").displayOrder(1).widthPx(200),
						new ColumnPreference().columnCode("email").displayOrder(2).visible(false))))));

		WorkspaceGrid userGrid = inScope(() -> workspaceApi.myGrids().getBody().getData()).stream()
				.filter(grid -> grid.getGridCode().equals("USER_MANAGEMENT")).findFirst().orElseThrow();
		assertThat(userGrid.getColumns()).hasSize(2);

		// Declarative replace: one column now, one column stored.
		inScope(() -> workspaceApi.replaceMyGridPreferences(List.of(new GridPreferences()
				.gridCode("USER_MANAGEMENT")
				.columns(List.of(new ColumnPreference().columnCode("displayName").displayOrder(1))))));
		userGrid = inScope(() -> workspaceApi.myGrids().getBody().getData()).stream()
				.filter(grid -> grid.getGridCode().equals("USER_MANAGEMENT")).findFirst().orElseThrow();
		assertThat(userGrid.getColumns()).hasSize(1);
	}

	@Test
	@DisplayName("saved filters: name unique per (user, grid), at most one default, and only the owner can delete")
	void savedFilterRules() {
		var first = inScope(() -> workspaceApi.createMyFilter(new CreateFilterRequest()
				.gridCode("USER_MANAGEMENT").name("Active only")
				.filterJson("{\"field\":\"retired\",\"op\":\"eq\",\"value\":false}")
				.isDefault(true)).getBody().getData());

		assertThatThrownBy(() -> inScope(() -> workspaceApi.createMyFilter(new CreateFilterRequest()
				.gridCode("USER_MANAGEMENT").name("Active only").filterJson("{}"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("CONFLICT"));

		// A second default steps the first down rather than failing or doubling.
		inScope(() -> workspaceApi.createMyFilter(new CreateFilterRequest()
				.gridCode("USER_MANAGEMENT").name("Retired only").filterJson("{}").isDefault(true)));
		assertThat(core.queryForObject(
				"SELECT COUNT(*) FROM saved_filter WHERE is_default = 1 AND retired_at IS NULL", Long.class))
				.isEqualTo(1);

		// Another user's filter is indistinguishable from a missing one.
		core.update("INSERT INTO user_account (external_id, display_name, email, role_id, keycloak_subject) "
				+ "SELECT 'usr-other', 'Other', 'other@example.test', role_id, 'kc-other' "
				+ "FROM role WHERE external_id = 'rol-it'");
		callerSubject = "kc-other";
		assertThatThrownBy(() -> inScope(() -> workspaceApi.deleteMyFilter(first.getExternalId())))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("NOT_FOUND"));

		callerSubject = SUBJECT;
		var remaining = inScope(() -> workspaceApi.deleteMyFilter(first.getExternalId())
				.getBody().getData());
		assertThat(remaining).extracting(f -> f.getName()).containsExactly("Retired only");
	}

	@Test
	@DisplayName("an unknown grid code and a duplicated column code are typed refusals, not 500s")
	void gridPreferenceRefusals() {
		assertThatThrownBy(() -> inScope(() -> workspaceApi.replaceMyGridPreferences(List.of(
				new GridPreferences().gridCode("NO_SUCH_GRID")
						.columns(List.of(new ColumnPreference().columnCode("a").displayOrder(1)))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("GRID_UNKNOWN"));

		assertThatThrownBy(() -> inScope(() -> workspaceApi.replaceMyGridPreferences(List.of(
				new GridPreferences().gridCode("USER_MANAGEMENT")
						.columns(List.of(
								new ColumnPreference().columnCode("email").displayOrder(1),
								new ColumnPreference().columnCode("email").displayOrder(2)))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("a branding request repeating a color or language code is refused as validation — in the INDEX's collation")
	void duplicateBrandingCodesAreRefusedUpFront() {
		// Deliberately case-variant: the unique indexes compare case-insensitively,
		// so a guard that only caught exact-case repeats would let this through
		// to a 500 (skeptical review, finding A3).
		assertThatThrownBy(() -> inScope(() -> brandingApi.updateSiteBranding(SITE,
				new UpdateSiteBrandingRequest().colors(List.of(
						new SiteColorItem().code("PRIMARY").hexValue("#111111"),
						new SiteColorItem().code("primary").hexValue("#222222"))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));

		assertThatThrownBy(() -> inScope(() -> brandingApi.updateSiteBranding(SITE,
				new UpdateSiteBrandingRequest().languages(List.of(
						new SiteLanguageItem().code("en").name("English"),
						new SiteLanguageItem().code("EN").name("Also English"))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));

		assertThat(core.queryForObject("SELECT COUNT(*) FROM site_color", Long.class)).isZero();
		assertThat(core.queryForObject("SELECT COUNT(*) FROM site_language", Long.class)).isZero();
	}

	@Test
	@DisplayName("a filter name differing only in case is the 409 the database means, not a 500")
	void caseVariantFilterNameIsAConflict() {
		inScope(() -> workspaceApi.createMyFilter(new CreateFilterRequest()
				.gridCode("USER_MANAGEMENT").name("Trucks").filterJson("{}")));

		assertThatThrownBy(() -> inScope(() -> workspaceApi.createMyFilter(new CreateFilterRequest()
				.gridCode("USER_MANAGEMENT").name("trucks").filterJson("{}"))))
				.as("the CI unique index fires on the case variant; the diagnosis must speak its collation")
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("CONFLICT"));
	}

	@Test
	@DisplayName("a token that maps to no platform user is USER_NOT_LINKED, not an empty workspace")
	void anUnlinkedTokenIsRefused() {
		callerSubject = "kc-stranger";
		assertThatThrownBy(() -> inScope(() -> workspaceApi.myGrids()))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("USER_NOT_LINKED"));
	}

	// ------------------------------------------------------------------------
	// Branding.
	// ------------------------------------------------------------------------

	@Test
	@DisplayName("colors normalize to #RRGGBBAA on write, an invalid value is refused, and languages round-trip")
	void brandingNormalizesAndRoundTrips() {
		var branding = inScope(() -> brandingApi.updateSiteBranding(SITE,
				new UpdateSiteBrandingRequest()
						.colors(List.of(
								new SiteColorItem().code("PRIMARY").hexValue("#1a2b3c"),
								new SiteColorItem().code("ALERT").hexValue("FF000080")))
						.languages(List.of(
								new SiteLanguageItem().code("en").name("English"))))
				.getBody().getData());

		assertThat(branding.getColors()).extracting(SiteColorItem::getHexValue)
				.as("6-digit input gains opaque alpha; case folds; 1.x's mixed formats cannot recur")
				.containsExactlyInAnyOrder("#1A2B3CFF", "#FF000080");
		assertThat(branding.getLanguages()).hasSize(1);

		assertThatThrownBy(() -> inScope(() -> brandingApi.updateSiteBranding(SITE,
				new UpdateSiteBrandingRequest().colors(List.of(
						new SiteColorItem().code("BAD").hexValue("reddish"))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("VALIDATION_FAILED"));

		assertThatThrownBy(() -> core.update(
				"INSERT INTO site_color (site_external_id, code, hex_value) VALUES (?, 'PRIMARY', '#00000000')",
				SITE))
				.as("(site, color code) is unique among active rows")
				.isInstanceOf(DuplicateKeyException.class);

		assertThatThrownBy(() -> inScope(() -> brandingApi.updateSiteBranding("SITE-NOWHERE",
				new UpdateSiteBrandingRequest().colors(List.of()))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("SITE_UNKNOWN"));
	}

	// ------------------------------------------------------------------------

	private <T> T inScope(Callable<T> work) {
		return ScopeContext.callIn(CoreScopes.installation(SITE), work);
	}
}
