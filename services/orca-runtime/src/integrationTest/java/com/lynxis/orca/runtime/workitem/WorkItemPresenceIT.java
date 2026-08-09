package com.lynxis.orca.runtime.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.workitem.api.WorkItemIntake;
import com.lynxis.orca.runtime.workitem.domain.PresenceService;
import com.lynxis.orca.runtime.workitem.domain.PresenceTables.UserActivity;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;
import com.lynxis.orca.runtime.workitem.persistence.PresenceRepository;
import com.lynxis.orca.runtime.workitem.persistence.RoutingReadRepository;
import com.lynxis.orca.runtime.workitem.persistence.WorkItemRepository;

/**
 * <strong>Proves operator presence and the Push path that consumes it.</strong>
 *
 * <p>The open activity row is the current status (there is no status column on
 * any user), "at most one open row per operator" holds under a race because the
 * filtered unique index is the guard, and Push pre-assigns an <em>assignable</em>
 * (IDLE/WORKING) member of an eligible team — deterministically, where 1.x
 * iterated a Go map and answered differently per run.
 */
class WorkItemPresenceIT {

	private static final String SCHEMA = "runtime";
	private static final String SITE = "SITE-IT";
	private static final String LANE = "LANE-IT-01";

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;

	private PresenceService presence;
	private WorkItemService workItems;
	private long executionId;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		jdbc = new JdbcTemplate(dataSource);
		RoutingTopologyFixture.publish(WorkItemPresenceIT::admin, "it_" + SCHEMA);
	}

	@BeforeEach
	void freshState() {
		jdbc.execute("DELETE FROM work_item_audit");
		jdbc.execute("DELETE FROM work_item");
		jdbc.execute("DELETE FROM user_activity");
		jdbc.execute("DELETE FROM execution");
		for (String table : List.of("topology_screen", "topology_team_routing", "topology_team_member")) {
			admin("DELETE FROM core." + table);
		}

		ScopeSeam seam = new JdbcScopeSeam(jdbc);
		TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
		presence = new PresenceService(new PresenceRepository(seam), transactions, SITE);
		workItems = new WorkItemService(new WorkItemRepository(seam), new RoutingReadRepository(seam),
				presence, taskId -> {
				}, transactions, SITE);

		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status) "
				+ "VALUES (?, ?, 999, 'MANUAL')", "vis-presence-" + UUID.randomUUID(), SITE);
		executionId = jdbc.queryForObject("SELECT MAX(execution_id) FROM execution", Long.class);
	}

	// ------------------------------------------------------------------------

	@Test
	@DisplayName("presence round-trips: transitions close-and-open, the open row is the status, history keeps the durations")
	void presenceRoundTrips() {
		assertThat(inScope(() -> presence.presenceOf("op-a")))
				.as("an operator with no transition ever recorded is OFFLINE")
				.isEqualTo("OFFLINE");

		inScope(() -> presence.setPresence("op-a", UserActivity.IDLE));
		assertThat(inScope(() -> presence.presenceOf("op-a"))).isEqualTo("IDLE");

		inScope(() -> presence.setPresence("op-a", UserActivity.WORKING));
		inScope(() -> presence.setPresence("op-a", UserActivity.BREAK));
		assertThat(inScope(() -> presence.presenceOf("op-a"))).isEqualTo("BREAK");

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_activity WHERE "
				+ "user_external_id = 'op-a' AND ended_at IS NULL", Long.class))
				.as("exactly one open row — the invariant the filtered unique index holds")
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_activity WHERE "
				+ "user_external_id = 'op-a' AND ended_at IS NOT NULL", Long.class))
				.as("every earlier state is a CLOSED row — the history the durations read needs")
				.isEqualTo(2);
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("two racing transitions leave exactly ONE open row — the filtered unique index is the guard, 200 times")
	void racingTransitionsLeaveOneOpenRow() throws Exception {
		ExecutorService racers = Executors.newFixedThreadPool(2);
		try {
			for (int i = 0; i < 200; i++) {
				String operator = "op-race-" + i;
				CyclicBarrier line = new CyclicBarrier(2);
				Future<?> a = racers.submit(() -> {
					line.await(10, TimeUnit.SECONDS);
					inScope(() -> presence.setPresence(operator, UserActivity.IDLE));
					return null;
				});
				Future<?> b = racers.submit(() -> {
					line.await(10, TimeUnit.SECONDS);
					inScope(() -> presence.setPresence(operator, UserActivity.DND));
					return null;
				});
				a.get();
				b.get();

				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_activity WHERE "
						+ "user_external_id = ? AND ended_at IS NULL", Long.class, operator))
						.as("iteration %d: whichever order the two landed in, one row is open", i)
						.isEqualTo(1);
			}
		}
		finally {
			racers.shutdownNow();
		}
	}

	@Test
	@DisplayName("Push pre-assigns an IDLE eligible operator at creation — deterministically — and the item stays QUEUED")
	void pushAssignsAnIdleEligibleOperator() {
		pushWorld();
		member("team-push", "op-dnd");
		member("team-push", "op-idle-late");
		member("team-push", "op-idle-early");
		inScope(() -> presence.setPresence("op-dnd", UserActivity.DND));
		inScope(() -> presence.setPresence("op-idle-late", UserActivity.IDLE));
		sleep(50);
		// Deliberately set AFTER op-idle-late, so "longest in state first" must
		// pick op-idle-late — insertion order cannot fake determinism here.
		inScope(() -> presence.setPresence("op-idle-early", UserActivity.IDLE));

		String item = raiseManualStep();

		assertThat(jdbc.queryForMap("SELECT status, assignee FROM work_item WHERE external_id = ?", item))
				.as("Push = PRE-ASSIGN: the assignable operator longest in IDLE, and the item "
						+ "stays QUEUED — the assignee still takes it (sheet §1)")
				.containsEntry("status", "QUEUED")
				.containsEntry("assignee", "op-idle-late");
		assertThat(jdbc.queryForList("SELECT actor FROM work_item_audit wa JOIN work_item w ON "
						+ "w.work_item_id = wa.work_item_id WHERE w.external_id = ? AND wa.action = 'ASSIGN'",
				String.class, item))
				.containsExactly("system:router");
	}

	@Test
	@DisplayName("Push with nobody assignable leaves the item unassigned and visible — never parked on someone who cannot act")
	void pushWithNobodyAssignableLeavesTheItemUnassigned() {
		pushWorld();
		member("team-push", "op-dnd");
		member("team-push", "op-break");
		inScope(() -> presence.setPresence("op-dnd", UserActivity.DND));
		inScope(() -> presence.setPresence("op-break", UserActivity.BREAK));
		// A third member with NO presence row at all is OFFLINE — also not assignable.
		member("team-push", "op-never-seen");

		String item = raiseManualStep();

		assertThat(jdbc.queryForMap("SELECT status, assignee FROM work_item WHERE external_id = ?", item))
				.containsEntry("status", "QUEUED")
				.containsEntry("assignee", null);
	}

	@Test
	@DisplayName("a PROMPT team never gets a push assignment — broadcast is the notify hub's, later")
	void promptNeverAssigns() {
		screen();
		rule("team-prompt", "PROMPT", null);
		member("team-prompt", "op-idle");
		inScope(() -> presence.setPresence("op-idle", UserActivity.IDLE));

		String item = raiseManualStep();

		assertThat(jdbc.queryForMap("SELECT assignee FROM work_item WHERE external_id = ?", item))
				.containsEntry("assignee", null);
	}

	// ------------------------------------------------------------------------

	private void pushWorld() {
		screen();
		rule("team-push", "PUSH", 1);
	}

	private void screen() {
		admin("INSERT INTO core.topology_screen (screen_external_id, screen_name, "
				+ "process_definition_key, node_reference, site_external_id) "
				+ "VALUES ('scr-push', N'Manual', 'gate-visit', 'manualInput', '" + SITE + "')");
	}

	private void rule(String team, String handling, Integer priority) {
		admin("INSERT INTO core.topology_team_routing (site_external_id, team_external_id, team_name, "
				+ "handling_method, screen_external_id, process_definition_key, node_reference, "
				+ "lane_external_id, priority) VALUES ('" + SITE + "', '" + team + "', N'" + team
				+ "', '" + handling + "', 'scr-push', 'gate-visit', 'manualInput', '" + LANE + "', "
				+ (priority == null ? "NULL" : priority) + ")");
	}

	private void member(String team, String user) {
		admin("INSERT INTO core.topology_team_member (site_external_id, team_external_id, "
				+ "user_external_id) VALUES ('" + SITE + "', '" + team + "', '" + user + "')");
	}

	/** Raises a manual step the way the engine listener does — through the intake, no engine needed. */
	private String raiseManualStep() {
		WorkItemIntake intake = workItems;
		inScope(() -> {
			intake.manualStepReached(new WorkItemIntake.ManualStep(
					"task-" + UUID.randomUUID(), "pi-presence", "gate-visit", "manualInput",
					executionId, 999, LANE, "vis-presence", null));
			return null;
		});
		return jdbc.queryForObject(
				"SELECT TOP 1 external_id FROM work_item ORDER BY work_item_id DESC", String.class);
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

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}
}
