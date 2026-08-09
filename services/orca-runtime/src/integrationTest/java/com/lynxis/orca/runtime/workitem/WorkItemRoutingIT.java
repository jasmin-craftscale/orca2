package com.lynxis.orca.runtime.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;
import com.lynxis.orca.runtime.workitem.domain.WorkItemTables.WorkItem;
import com.lynxis.orca.runtime.workitem.persistence.RoutingReadRepository;
import com.lynxis.orca.runtime.workitem.persistence.WorkItemRepository;

/**
 * <strong>Phase 3 WP2 · routing as properties</strong>: the claim respects
 * eligibility, and the grid's ordering is the sheet's — set priority before
 * unset, lower more urgent, oldest-queued as the tiebreak.
 *
 * <p>No engine here: eligibility and ordering are reads over the item rows and
 * core's published views, so the suite constructs its beans directly against the
 * migrated schema — the same shape as core's property suites. The engine half of
 * WP2 (screen resolution inside the creating transaction) is proven in
 * {@code WorkItemLifecycleIT}, which runs the real listener.
 */
class WorkItemRoutingIT {

	private static final String SCHEMA = "runtime";
	private static final String SITE = "SITE-IT";

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;

	private WorkItemService workItems;
	private long executionId;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		jdbc = new JdbcTemplate(dataSource);
		RoutingTopologyFixture.publish(WorkItemRoutingIT::admin, "it_" + SCHEMA);
	}

	@BeforeEach
	void freshState() {
		jdbc.execute("DELETE FROM work_item_audit");
		jdbc.execute("DELETE FROM work_item");
		jdbc.execute("DELETE FROM execution");
		for (String table : List.of("topology_screen", "topology_team_routing", "topology_team_member")) {
			admin("DELETE FROM core." + table);
		}

		ScopeSeam seam = new JdbcScopeSeam(jdbc);
		workItems = new WorkItemService(new WorkItemRepository(seam), new RoutingReadRepository(seam),
				taskId -> {
					// No engine in this suite; completion is WorkItemLifecycleIT's.
				},
				new TransactionTemplate(new JdbcTransactionManager(dataSource)), SITE);

		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status) "
				+ "VALUES (?, ?, 999, 'MANUAL')", "vis-routing-" + UUID.randomUUID(), SITE);
		executionId = jdbc.queryForObject("SELECT MAX(execution_id) FROM execution", Long.class);
	}

	// ------------------------------------------------------------------------

	@Test
	@DisplayName("the claim respects eligibility: a team member takes, an outsider is refused typed, a pre-assignee overrides")
	void theClaimRespectsEligibility() {
		screen("scr-a");
		rule("team-clerks", "PROMPT", "scr-a", "LANE-1", null);
		member("team-clerks", "op-in-team");

		insertItem("wi-routed", "scr-a", "LANE-1");
		insertItem("wi-routed-2", "scr-a", "LANE-1");
		insertItem("wi-routed-3", "scr-a", "LANE-1");

		// A member of the routed team claims.
		assertThat(inScope(() -> workItems.take("wi-routed", "op-in-team")).status())
				.isEqualTo(WorkItem.IN_PROGRESS);

		// An outsider is refused — typed, not silently no-op'd, and NOT a
		// conflict: nothing raced, the claim was never theirs.
		assertThatThrownBy(() -> inScope(() -> workItems.take("wi-routed-2", "op-outsider")))
				.isInstanceOf(WorkItemService.WorkItemIneligibleException.class)
				.hasMessageContaining("team-clerks");

		// A pre-assignment is a supervisor's deliberate act and overrides team
		// eligibility — the assignee may take even from outside the teams.
		inScope(() -> workItems.assign("wi-routed-3", "op-outsider", "op-supervisor"));
		assertThat(inScope(() -> workItems.take("wi-routed-3", "op-outsider")).status())
				.isEqualTo(WorkItem.IN_PROGRESS);
	}

	@Test
	@DisplayName("unrouted work is claimable by anyone — an item nobody may touch is worse than one everybody may")
	void unroutedWorkIsClaimableByAnyone() {
		// No screen identity at all.
		insertItem("wi-screenless", null, "LANE-1");
		assertThat(inScope(() -> workItems.take("wi-screenless", "op-anybody")).status())
				.isEqualTo(WorkItem.IN_PROGRESS);

		// A screen, but no routing rules for it.
		screen("scr-unruled");
		insertItem("wi-unruled", "scr-unruled", "LANE-1");
		assertThat(inScope(() -> workItems.take("wi-unruled", "op-anybody")).status())
				.isEqualTo(WorkItem.IN_PROGRESS);
	}

	@Test
	@DisplayName("the grid orders by the routing rules' priority: set before unset, lower first, FIFO as the tiebreak")
	void theGridOrderingIsTheSheets() {
		screen("scr-a");
		screen("scr-b");
		screen("scr-c");
		rule("team-clerks", "PROMPT", "scr-a", "LANE-1", 5);
		rule("team-clerks", "PROMPT", "scr-b", "LANE-1", 1);
		// scr-c has a rule with NO priority — ordered after the prioritised.
		rule("team-clerks", "PROMPT", "scr-c", "LANE-1", null);

		// Queued oldest-first in exactly the wrong order for priority, so the
		// ordering below cannot pass by accident of insertion.
		insertItem("wi-unprioritised-old", "scr-c", "LANE-1");
		insertItem("wi-p5", "scr-a", "LANE-1");
		insertItem("wi-p1", "scr-b", "LANE-1");
		insertItem("wi-unprioritised-new", "scr-c", "LANE-1");

		List<String> ordered = inScope(() -> workItems.list("QUEUED", null, null, null, 100))
				.stream().map(WorkItem::externalId).toList();

		assertThat(ordered).containsExactly(
				"wi-p1",                  // priority 1 — most urgent
				"wi-p5",                  // priority 5
				"wi-unprioritised-old",   // no priority: after the prioritised, FIFO…
				"wi-unprioritised-new");
	}

	@Test
	@DisplayName("a team-filtered grid shows the team's routed work plus the unrouted, ordered by THAT team's priorities")
	void theTeamFilteredGridUsesTheTeamsPriorities() {
		screen("scr-a");
		screen("scr-b");
		rule("team-a", "PROMPT", "scr-a", "LANE-1", 1);
		rule("team-b", "PROMPT", "scr-b", "LANE-1", 1);
		// team-b also handles scr-a, at a WORSE priority than team-a does.
		rule("team-b", "PROMPT", "scr-a", "LANE-1", 9);

		insertItem("wi-a", "scr-a", "LANE-1");
		insertItem("wi-b", "scr-b", "LANE-1");
		insertItem("wi-unrouted", null, "LANE-1");

		List<String> teamB = inScope(() -> workItems.list("QUEUED", null, null, "team-b", 100))
				.stream().map(WorkItem::externalId).toList();

		assertThat(teamB)
				.as("team-b sees both routed items — ordered by ITS priorities (scr-b at 1 "
						+ "beats scr-a at 9) — and the unrouted item last (no priority)")
				.containsExactly("wi-b", "wi-a", "wi-unrouted");

		List<String> teamA = inScope(() -> workItems.list("QUEUED", null, null, "team-a", 100))
				.stream().map(WorkItem::externalId).toList();

		assertThat(teamA)
				.as("team-a is not routed to scr-b, so wi-b is not its work")
				.containsExactly("wi-a", "wi-unrouted");
	}

	// ------------------------------------------------------------------------

	private void screen(String externalId) {
		admin("INSERT INTO core.topology_screen (screen_external_id, screen_name, "
				+ "process_definition_key, node_reference, site_external_id) VALUES ('" + externalId
				+ "', N'" + externalId + "', 'gate-visit', 'manualInput', '" + SITE + "')");
	}

	private void rule(String team, String handling, String screen, String lane, Integer priority) {
		admin("INSERT INTO core.topology_team_routing (site_external_id, team_external_id, team_name, "
				+ "handling_method, screen_external_id, process_definition_key, node_reference, "
				+ "lane_external_id, priority) VALUES ('" + SITE + "', '" + team + "', N'" + team
				+ "', '" + handling + "', '" + screen + "', 'gate-visit', 'manualInput', '" + lane
				+ "', " + (priority == null ? "NULL" : priority) + ")");
	}

	private void member(String team, String user) {
		admin("INSERT INTO core.topology_team_member (site_external_id, team_external_id, "
				+ "user_external_id) VALUES ('" + SITE + "', '" + team + "', '" + user + "')");
	}

	/** Insertion order = queue order, made explicit: two same-millisecond rows would tie the FIFO tiebreak. */
	private int queuedSequence;

	private void insertItem(String externalId, String screenExternalId, String laneExternalId) {
		jdbc.update("INSERT INTO work_item (external_id, site_external_id, execution_id, lane_id, "
						+ "visit_external_id, lane_external_id, process_instance_id, task_id, "
						+ "process_definition_key, node_reference, screen_external_id, status, queued_at) "
						+ "VALUES (?, ?, ?, 999, 'vis-fake', ?, 'pi-fake', ?, 'gate-visit', 'manualInput', ?, "
						+ "'QUEUED', DATEADD(SECOND, ?, '2026-08-09T00:00:00'))",
				externalId, SITE, executionId, laneExternalId, "task-" + externalId, screenExternalId,
				queuedSequence++);
	}

	private <T> T inScope(Supplier<T> action) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(SITE)), action::get);
	}

	private static void admin(String sql) {
		try (Connection connection = PlatformDatabase.administrative().getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed: " + sql, e);
		}
	}
}
