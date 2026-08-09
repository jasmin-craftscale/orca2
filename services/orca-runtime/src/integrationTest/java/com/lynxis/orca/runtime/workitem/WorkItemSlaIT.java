package com.lynxis.orca.runtime.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.InboundDeviceEvent;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;

import com.sun.net.httpserver.HttpServer;

/**
 * <strong>Proves by firing it that the SLA is a real engine timer.</strong>
 *
 * <p>A boundary timer on the manual-input <em>wait state</em> fires, where a timer
 * attached to a synchronous service task could not: the wait state genuinely
 * parks, so the timer job is committed and visible to the async executor.
 *
 * <ul>
 *   <li><strong>It fires</strong>: an item left past its threshold gets
 *       {@code sla_breached_at} and an {@code SLA_BREACH} audit row — and the
 *       breach branch does NOT close the visit or disturb the item.</li>
 *   <li><strong>It survives a restart and fires once</strong>: the timer is
 *       engine state in the database; the instance that armed it dies, a
 *       different instance fires it, proving the SLA survives a restart.</li>
 *   <li><strong>It does not fire falsely</strong>: an item completed inside its
 *       threshold never breaches, and the timer job dies with the task.</li>
 * </ul>
 *
 * <p>Boots the shipping application (twice, for the restart) the way
 * {@code RuntimeRestartIT} does — no {@code @SpringBootTest} context to share,
 * because the whole point of the second boot is that it shares nothing.
 */
class WorkItemSlaIT {

	private static final Logger log = LoggerFactory.getLogger(WorkItemSlaIT.class);

	private static final String SCHEMA = "runtime";
	private static final String SITE = "SITE-IT";
	private static final String LANE = "LANE-IT-01";

	/** Short enough to watch fire, long enough to admit and (in one test) kill an instance first. */
	private static final int THRESHOLD_SEC = 8;

	private static DriverManagerDataSource dataSource;
	private static JdbcTemplate jdbc;
	private static HttpServer edge;

	@BeforeAll
	static void migrateAndPublish() {
		dataSource = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		jdbc = new JdbcTemplate(dataSource);

		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT CAST(1 AS BIGINT) AS lane_id, "
				+ "''" + LANE + "'' AS lane_external_id, ''" + SITE + "'' AS site_external_id')");
		admin("GRANT SELECT ON core.topology_lane TO [it_" + SCHEMA + "]");
		RoutingTopologyFixture.publish(WorkItemSlaIT::admin, "it_" + SCHEMA);

		// A quiet edge stub, so the happy-path branch has somewhere to send a
		// command if a test ever routes there. The connector is deliberately
		// UNCONFIGURED — every truck takes the failure branch into the wait state.
		edge = start("/internal/commands/v1", exchange ->
				respond(exchange, 200, "{\"status\":\"SUCCESS\",\"data\":{\"commandId\":\"x\","
						+ "\"status\":\"EXECUTED\",\"deviceResponse\":\"{}\"}}"));
	}

	@AfterAll
	static void stopFakes() {
		if (edge != null) {
			edge.stop(0);
		}
	}

	@BeforeEach
	void freshState() {
		for (String table : List.of("work_item_audit", "work_item", "outbox_delivery", "outbox",
				"execution_event", "execution", "lane_session", "idempotency_record")) {
			jdbc.execute("DELETE FROM " + table);
		}
		for (String table : List.of("topology_screen", "topology_team_routing",
				"topology_team_member", "topology_operator", "topology_setting")) {
			admin("DELETE FROM core." + table);
		}
		// The screen identity whose max_sec arms the timer.
		admin("INSERT INTO core.topology_screen (screen_external_id, screen_name, "
				+ "process_definition_key, node_reference, expected_sec, max_sec, site_external_id) "
				+ "VALUES ('scr-sla', N'Manual handling', 'gate-visit', 'manualInput', 4, "
				+ THRESHOLD_SEC + ", '" + SITE + "')");
	}

	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 15, unit = TimeUnit.MINUTES)
	@DisplayName("THE CLAIM: the boundary timer on the wait state FIRES — the breach is recorded, and nothing else moves")
	void theTimerFiresAndRecordsTheBreach() {
		try (ConfigurableApplicationContext app = boot(true)) {
			String visit = admitThrough(app);
			String item = awaitQueuedItem(visit);

			// The engine holds a REAL timer job for the instance, which a synchronous
			// service task can never expose outside its transaction.
			assertThat(count("SELECT COUNT(*) FROM ACT_RU_TIMER_JOB"))
					.as("a committed timer job, visible outside the transaction that armed it "
							+ "— the precondition §7.1's service-task case could never meet")
					.isEqualTo(1);

			awaitBreach(item);

			// The breach marked the item and NOTHING else moved: the item is still
			// QUEUED and claimable (non-interrupting), the process still parks, the
			// visit is still ACTIVE — a breach is information, not an intervention.
			assertThat(jdbc.queryForMap("SELECT status, assignee FROM work_item WHERE external_id = ?", item))
					.containsEntry("status", "QUEUED")
					.containsEntry("assignee", null);
			assertThat(statusOf(visit))
					.as("the breach branch's own end event must NOT close the visit — the truck "
							+ "is still standing at the gate")
					.isEqualTo("ACTIVE");
			assertThat(jdbc.queryForList("SELECT action FROM work_item_audit wa JOIN work_item w ON "
							+ "w.work_item_id = wa.work_item_id WHERE w.external_id = ? ORDER BY wa.occurred_at",
					String.class, item))
					.containsExactly("SLA_BREACH");

			// And the breached item still completes normally — breach detection is
			// the narrow version, deliberately: no requeue, no escalation policy.
			WorkItemService workItems = app.getBean(WorkItemService.class);
			ScopeContext.runIn(Scope.of("site_external_id", Set.of(SITE)), () -> {
				workItems.take(item, "op-clerk");
				workItems.complete(item, "op-clerk", null);
			});
			assertThat(statusOf(visit)).isEqualTo("MANUAL");
			assertThat(count("SELECT COUNT(*) FROM ACT_RU_TIMER_JOB")).isZero();
		}
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("§B10: the timer SURVIVES A RESTART — armed by one instance, fired by another, recorded exactly once")
	void theTimerSurvivesARestartAndFiresOnce() {
		String visit;
		String item;

		// --- 1 · the instance that parks the visit and arms the timer, then dies.
		try (ConfigurableApplicationContext first = boot(true)) {
			visit = admitThrough(first);
			item = awaitQueuedItem(visit);
			assertThat(count("SELECT COUNT(*) FROM ACT_RU_TIMER_JOB")).isEqualTo(1);
			assertThat(breachedAt(item)).isNull();
			log.info("WP3 · timer armed for item {} by an instance that is about to die", item);
		}
		// The context is closed. Its engine, its executor: gone. The timer job is
		// rows in ACT_RU_TIMER_JOB — engine state in the database, nowhere else.

		assertThat(breachedAt(item))
				.as("nothing fired while no instance was running — the timer is not a JVM object")
				.isNull();

		// --- 2 · a DIFFERENT instance, which never saw the item, fires the breach.
		try (ConfigurableApplicationContext second = boot(true)) {
			awaitBreach(item);

			assertThat(count("SELECT COUNT(*) FROM work_item_audit WHERE action = 'SLA_BREACH'"))
					.as("fired ONCE across the restart: one breach record, one audit row — the "
							+ "sla_breached_at IS NULL predicate is the guard, and the dead "
							+ "instance's timer did not double-fire")
					.isEqualTo(1);
		}
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.MINUTES)
	@DisplayName("an item completed INSIDE its threshold never breaches — the timer dies with the task")
	void completionInsideTheThresholdKillsTheTimer() {
		try (ConfigurableApplicationContext app = boot(true)) {
			String visit = admitThrough(app);
			String item = awaitQueuedItem(visit);

			WorkItemService workItems = app.getBean(WorkItemService.class);
			ScopeContext.runIn(Scope.of("site_external_id", Set.of(SITE)), () -> {
				workItems.take(item, "op-clerk");
				workItems.complete(item, "op-clerk", null);
			});

			assertThat(count("SELECT COUNT(*) FROM ACT_RU_TIMER_JOB"))
					.as("completing the wait state deletes its boundary timer job in the same "
							+ "transaction")
					.isZero();

			// Wait out the threshold: nothing may fire afterwards.
			sleep((THRESHOLD_SEC + 4) * 1_000L);
			assertThat(breachedAt(item))
					.as("a breach recorded after completion would be a false alarm on a done item")
					.isNull();
			assertThat(count("SELECT COUNT(*) FROM work_item_audit WHERE action = 'SLA_BREACH'"))
					.isZero();
		}
	}

	// ------------------------------------------------------------------------

	private static ConfigurableApplicationContext boot(boolean asyncExecutor) {
		return new SpringApplicationBuilder(RuntimeApplication.class)
				.web(WebApplicationType.NONE)
				.run(
						"--spring.datasource.url=" + dataSource.getUrl(),
						"--spring.datasource.username=" + dataSource.getUsername(),
						"--spring.datasource.password=" + dataSource.getPassword(),
						"--spring.jpa.properties.hibernate.default_schema=" + SCHEMA,
						"--spring.flyway.schemas=" + SCHEMA,
						"--spring.flyway.default-schema=" + SCHEMA,
						"--orca.required-views=",
						"--orca.installation.site-external-id=" + SITE,
						"--orca.internal.shared-credential=integration-test-credential-not-a-fixture",
						"--orca.runtime.edge-base-url=http://localhost:" + edge.getAddress().getPort(),
						// Fire timers promptly: the executor's default acquire wait is
						// 10s, which on an 8s threshold reads as "did not fire".
						"--flowable.process.async-executor.default-timer-job-acquire-wait-time=PT1S",
						"--flowable.async-executor-activate=" + asyncExecutor);
	}

	private static String admitThrough(ConfigurableApplicationContext app) {
		AdmissionService admission = app.getBean(AdmissionService.class);
		AdmissionService.EventOutcome outcome = ScopeContext.callIn(
				Scope.of("site_external_id", Set.of(SITE)),
				() -> admission.accept(new InboundDeviceEvent("evt-" + UUID.randomUUID(), LANE,
						"DEV-IT-CAMERA", "lpr.capture", "{\"plate\":\"T-SLA-01\"}", null)));
		return outcome.visitExternalId();
	}

	private static String awaitQueuedItem(String visitExternalId) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
		while (System.nanoTime() < deadline) {
			List<String> queued = jdbc.queryForList(
					"SELECT external_id FROM work_item WHERE visit_external_id = ?",
					String.class, visitExternalId);
			if (!queued.isEmpty()) {
				return queued.getFirst();
			}
			sleep(200);
		}
		throw new AssertionError("no work item ever appeared for visit " + visitExternalId);
	}

	private static void awaitBreach(String itemExternalId) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (breachedAt(itemExternalId) != null) {
				return;
			}
			sleep(500);
		}
		throw new AssertionError("""
				work item %s never breached its SLA — THE TIMER DID NOT FIRE.

				This is the phase's halt condition (plan §5): if a boundary timer on the \
				wait state cannot be made to fire either, the SLA design changes and the \
				phase stops and reports, alongside phase-1 §7.1. Do not work around it.\
				""".formatted(itemExternalId));
	}

	private static Object breachedAt(String itemExternalId) {
		return jdbc.queryForObject("SELECT sla_breached_at FROM work_item WHERE external_id = ?",
				Object.class, itemExternalId);
	}

	private static String statusOf(String visitExternalId) {
		return jdbc.queryForObject("SELECT status FROM execution WHERE external_id = ?",
				String.class, visitExternalId);
	}

	private static long count(String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0 : counted;
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

	private static HttpServer start(String path, com.sun.net.httpserver.HttpHandler handler) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext(path, handler);
			server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			return server;
		}
		catch (java.io.IOException cannotBind) {
			throw new IllegalStateException(cannotBind);
		}
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
			throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
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
