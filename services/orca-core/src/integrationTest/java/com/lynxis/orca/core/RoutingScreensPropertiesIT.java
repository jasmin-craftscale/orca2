package com.lynxis.orca.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.core.api.ScreenAdminController;
import com.lynxis.orca.core.api.TeamRoutingController;
import com.lynxis.orca.core.api.generated.model.CreateScreenRequest;
import com.lynxis.orca.core.api.generated.model.ReplaceRoutingRulesRequest;
import com.lynxis.orca.core.api.generated.model.RoutingRule;
import com.lynxis.orca.core.api.generated.model.ScreenSummary;
import com.lynxis.orca.core.api.generated.model.UpdateScreenRequest;
import com.lynxis.orca.core.domain.AuditTrail;
import com.lynxis.orca.core.domain.RoutingAdminService;
import com.lynxis.orca.core.persistence.AuditEventRepository;
import com.lynxis.orca.core.persistence.DeviceRepository;
import com.lynxis.orca.core.persistence.ScreenRepository;
import com.lynxis.orca.core.persistence.TeamRepository;
import com.lynxis.orca.core.persistence.TeamRoutingRepository;
import com.lynxis.orca.core.persistence.UserAccountRepository;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.web.ApiException;

/**
 * Proves what the screens-and-routing schema refuses and what its views publish.
 *
 * <p>The load-bearing constraints, each watched to refuse a duplicate: one
 * active screen per node per site (routing must resolve to ONE screen), and the
 * routing tuple 1.x never constrained. Plus the published views — the contract
 * the runtime-side fixtures mirror, proven here against the real tables.
 */
class RoutingScreensPropertiesIT {

	private static final String SCHEMA = "core";
	private static final String SITE = "SITE-IT";

	private static DataSource owner;

	private JdbcTemplate core;
	private ScreenAdminController screensApi;
	private TeamRoutingController routingApi;

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
		core.execute("DELETE FROM team_routing");
		core.execute("DELETE FROM screen");
		core.execute("DELETE FROM team_member");
		core.execute("DELETE FROM team");
		core.execute("DELETE FROM user_account");
		core.execute("DELETE FROM role");
		core.execute("DELETE FROM device");
		core.execute("DELETE FROM lane");
		core.execute("DELETE FROM area");
		core.execute("DELETE FROM site");
		core.update("INSERT INTO site (external_id, code, name, is_primary) VALUES (?, 'IT', 'IT Terminal', 1)", SITE);
		core.update("INSERT INTO area (external_id, site_id, code, name) "
				+ "SELECT 'area-it', site_id, 'A', 'Area' FROM site WHERE external_id = ?", SITE);
		core.update("INSERT INTO lane (external_id, area_id, code, name) "
				+ "SELECT 'LANE-IT-01', area_id, 'L1', 'Lane 1' FROM area WHERE external_id = 'area-it'");
		core.update("INSERT INTO team (external_id, site_external_id, name, handling_method) "
				+ "VALUES ('team-clerks', ?, 'Clerks', 'PROMPT')", SITE);

		ScopeSeam seam = new JdbcScopeSeam(core);
		ScreenRepository screens = new ScreenRepository(seam);
		TeamRoutingRepository routing = new TeamRoutingRepository(seam);
		TeamRepository teams = new TeamRepository(seam);
		DeviceRepository devices = new DeviceRepository(seam);
		AuditTrail audit = new AuditTrail(new AuditEventRepository(seam),
				new UserAccountRepository(seam), Optional::empty, SITE);
		RoutingAdminService service = new RoutingAdminService(screens, routing, teams, devices, audit);
		screensApi = new ScreenAdminController(service, SITE);
		routingApi = new TeamRoutingController(service, SITE);
	}

	// ------------------------------------------------------------------------

	@Test
	@DisplayName("one ACTIVE screen per node per site — the database refuses the second, and retirement frees the node")
	void oneActiveScreenPerNode() {
		insertScreen("scr-1", "gate-visit", "manualInput");

		assertThatThrownBy(() -> insertScreen("scr-2", "gate-visit", "manualInput"))
				.as("routing must resolve to ONE screen; two identities for one node is 1.x's "
						+ "load-bearing-strings world one layer down")
				.isInstanceOf(DuplicateKeyException.class);

		core.update("UPDATE screen SET retired_at = SYSUTCDATETIME() WHERE external_id = 'scr-1'");
		insertScreen("scr-3", "gate-visit", "manualInput");
	}

	@Test
	@DisplayName("the routing tuple is unique among active rules — the constraint 1.x never had")
	void theRoutingTupleIsUnique() {
		insertScreen("scr-1", "gate-visit", "manualInput");
		long teamId = core.queryForObject("SELECT team_id FROM team WHERE external_id = 'team-clerks'", Long.class);
		long screenId = core.queryForObject("SELECT screen_id FROM screen WHERE external_id = 'scr-1'", Long.class);
		long laneId = core.queryForObject("SELECT lane_id FROM lane WHERE external_id = 'LANE-IT-01'", Long.class);

		core.update("INSERT INTO team_routing (external_id, site_external_id, team_id, screen_id, lane_id) "
				+ "VALUES ('rt-1', ?, ?, ?, ?)", SITE, teamId, screenId, laneId);
		assertThatThrownBy(() -> core.update(
				"INSERT INTO team_routing (external_id, site_external_id, team_id, screen_id, lane_id) "
						+ "VALUES ('rt-2', ?, ?, ?, ?)", SITE, teamId, screenId, laneId))
				.isInstanceOf(DuplicateKeyException.class);

		// Retired rules do not block a re-grant — the declarative-set replace shape.
		core.update("UPDATE team_routing SET retired_at = SYSUTCDATETIME() WHERE external_id = 'rt-1'");
		core.update("INSERT INTO team_routing (external_id, site_external_id, team_id, screen_id, lane_id) "
				+ "VALUES ('rt-3', ?, ?, ?, ?)", SITE, teamId, screenId, laneId);
	}

	@Test
	@DisplayName("the API round-trips: create a screen, replace a team's rules declaratively, and the refusals are typed")
	void theApiRoundTrips() {
		ScreenSummary screen = inScope(() -> screensApi.createScreen(new CreateScreenRequest()
				.name("Manual handling")
				.processDefinitionKey("gate-visit")
				.nodeReference("manualInput")
				.maxSec(300)).getBody().getData());
		assertThat(screen.getMaxSec()).isEqualTo(300);

		// The duplicate node arrives as the typed 409, not a bare SQL error.
		assertThatThrownBy(() -> inScope(() -> screensApi.createScreen(new CreateScreenRequest()
				.name("Second identity").processDefinitionKey("gate-visit").nodeReference("manualInput"))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("SCREEN_NODE_TAKEN"));

		List<RoutingRule> rules = inScope(() -> routingApi.replaceTeamRoutingRules("team-clerks",
				new ReplaceRoutingRulesRequest().rules(List.of(
						new RoutingRule().screenExternalId(screen.getExternalId())
								.laneExternalId("LANE-IT-01").priority(3))))
				.getBody().getData());
		assertThat(rules).singleElement().satisfies(rule -> {
			assertThat(rule.getScreenExternalId()).isEqualTo(screen.getExternalId());
			assertThat(rule.getLaneExternalId()).isEqualTo("LANE-IT-01");
			assertThat(rule.getPriority()).isEqualTo(3);
		});

		// Replace is declarative: the empty set clears.
		assertThat(inScope(() -> routingApi.replaceTeamRoutingRules("team-clerks",
				new ReplaceRoutingRulesRequest().rules(List.of())).getBody().getData())).isEmpty();

		// The refusals, each with its own code.
		assertThatThrownBy(() -> inScope(() -> routingApi.replaceTeamRoutingRules("team-clerks",
				new ReplaceRoutingRulesRequest().rules(List.of(new RoutingRule()
						.screenExternalId(screen.getExternalId()).laneExternalId("LANE-NOWHERE"))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("LANE_UNKNOWN"));
		assertThatThrownBy(() -> inScope(() -> routingApi.replaceTeamRoutingRules("team-clerks",
				new ReplaceRoutingRulesRequest().rules(List.of(
						new RoutingRule().screenExternalId(screen.getExternalId()).laneExternalId("LANE-IT-01"),
						new RoutingRule().screenExternalId(screen.getExternalId()).laneExternalId("LANE-IT-01"))))))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code()).isEqualTo("ROUTING_RULE_DUPLICATE"));

		// A threshold of 0 clears back to the global-setting fallback.
		ScreenSummary cleared = inScope(() -> screensApi.updateScreen(screen.getExternalId(),
				new UpdateScreenRequest().maxSec(0)).getBody().getData());
		assertThat(cleared.getMaxSec()).isNull();
	}

	@Test
	@DisplayName("the published views serve the contract the runtime fixtures mirror — columns, joins and the operator realm")
	void theViewsServeTheContract() {
		ScreenSummary screen = inScope(() -> screensApi.createScreen(new CreateScreenRequest()
				.name("Manual handling").processDefinitionKey("gate-visit").nodeReference("manualInput")
				.expectedSec(60).maxSec(300)).getBody().getData());
		inScope(() -> routingApi.replaceTeamRoutingRules("team-clerks",
				new ReplaceRoutingRulesRequest().rules(List.of(
						new RoutingRule().screenExternalId(screen.getExternalId())
								.laneExternalId("LANE-IT-01").priority(2)))));

		long roleId = insertRole();
		core.update("INSERT INTO user_account (external_id, display_name, email, role_id, keycloak_subject) "
				+ "VALUES ('usr-clerk', 'Clerk', 'clerk@example.test', " + roleId + ", 'kc-sub-1')");
		core.update("INSERT INTO team_member (team_id, user_id, site_external_id) "
				+ "SELECT t.team_id, u.user_id, ? FROM team t, user_account u "
				+ "WHERE t.external_id = 'team-clerks' AND u.external_id = 'usr-clerk'", SITE);

		Map<String, Object> routing = core.queryForMap("SELECT * FROM topology_team_routing");
		assertThat(routing).containsEntry("site_external_id", SITE)
				.containsEntry("team_external_id", "team-clerks")
				.containsEntry("handling_method", "PROMPT")
				.containsEntry("screen_external_id", screen.getExternalId())
				.containsEntry("process_definition_key", "gate-visit")
				.containsEntry("node_reference", "manualInput")
				.containsEntry("lane_external_id", "LANE-IT-01")
				.containsEntry("priority", 2);

		Map<String, Object> member = core.queryForMap("SELECT * FROM topology_team_member");
		assertThat(member).containsEntry("team_external_id", "team-clerks")
				.containsEntry("user_external_id", "usr-clerk");

		Map<String, Object> operator = core.queryForMap(
				"SELECT * FROM topology_operator WHERE keycloak_subject = 'kc-sub-1'");
		assertThat(operator).containsEntry("config_realm", "INSTALLATION")
				.containsEntry("user_external_id", "usr-clerk");

		Map<String, Object> screenRow = core.queryForMap("SELECT * FROM topology_screen");
		assertThat(screenRow).containsEntry("screen_external_id", screen.getExternalId())
				.containsEntry("expected_sec", 60)
				.containsEntry("max_sec", 300);

		// A retired screen leaves the views — and its rules with it.
		inScope(() -> screensApi.updateScreen(screen.getExternalId(),
				new UpdateScreenRequest().retired(true)));
		assertThat(core.queryForObject("SELECT COUNT(*) FROM topology_screen", Long.class)).isZero();
		assertThat(core.queryForObject("SELECT COUNT(*) FROM topology_team_routing", Long.class)).isZero();
	}

	// ------------------------------------------------------------------------

	private void insertScreen(String externalId, String processKey, String node) {
		core.update("INSERT INTO screen (external_id, site_external_id, name, process_definition_key, "
				+ "node_reference) VALUES (?, ?, 'S', ?, ?)", externalId, SITE, processKey, node);
	}

	private long insertRole() {
		core.update("INSERT INTO role (external_id, name) VALUES ('rol-it', 'Clerk role')");
		return core.queryForObject("SELECT role_id FROM role WHERE external_id = 'rol-it'", Long.class);
	}

	private <T> T inScope(Supplier<T> action) {
		return ScopeContext.callIn(com.lynxis.orca.core.domain.CoreScopes.installation(SITE), action::get);
	}
}
