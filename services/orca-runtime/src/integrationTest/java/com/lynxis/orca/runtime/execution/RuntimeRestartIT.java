package com.lynxis.orca.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.InboundDeviceEvent;

import com.sun.net.httpserver.HttpServer;

/**
 * <strong>Kills runtime mid-visit and proves that a restarted instance finishes it.</strong>
 *
 * <p>The claim under test is that <em>engine state lives in the database and
 * nowhere else</em>. A process instance halfway through is not an object on a heap
 * and not a thread parked somewhere: it is rows, and a different process that
 * reads those rows continues from the step the first one reached.
 *
 * <p>So this boots the shipping application <strong>twice</strong>, in one JVM but
 * as two independent contexts, rather than using {@code @SpringBootTest} — because
 * the whole point is that the second one shares nothing with the first except the
 * schema.
 *
 * <ol>
 *   <li><strong>First instance, async executor OFF.</strong> A truck is admitted:
 *       the visit row and the process instance commit together, and the first
 *       service task is a queued job because nothing is running workers. This is
 *       "after admission, before completion" exactly.</li>
 *   <li><strong>The instance is killed.</strong> Its context is closed. Nothing is
 *       handed over.</li>
 *   <li><strong>Second instance, async executor ON.</strong> It reads the job it
 *       never created, calls the customer system, commands the barrier and closes
 *       the visit with its fact.</li>
 * </ol>
 */
class RuntimeRestartIT {

	private static final Logger log = LoggerFactory.getLogger(RuntimeRestartIT.class);

	private static final String SCHEMA = "runtime";
	private static final String SITE = "SITE-IT";
	private static final String LANE = "LANE-IT-01";

	private static final List<String> deviceCommands = new CopyOnWriteArrayList<>();

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("a visit killed mid-flight is picked up by a DIFFERENT instance and completes")
	void aVisitSurvivesTheDeathOfTheInstanceThatStartedIt() throws Exception {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		publishTopologyLane();
		grantTopologyLaneTo("it_" + SCHEMA);

		JdbcTemplate jdbc = new JdbcTemplate(migrated);
		clean(jdbc);

		HttpServer tos = start("/tos/v1/visits", exchange -> respond(exchange, 200, "{\"decision\":\"ok\"}"));
		HttpServer edge = start("/internal/commands/v1", exchange -> {
			deviceCommands.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"status\":\"SUCCESS\",\"data\":{\"commandId\":\"x\","
					+ "\"status\":\"EXECUTED\",\"deviceResponse\":\"{}\"}}");
		});

		jdbc.update("INSERT INTO connector_config (site_external_id, connector_name, base_url, "
						+ "request_path, deadline_ms, is_enabled) VALUES (?, 'tos', ?, '/tos/v1/visits', 4000, 1)",
				SITE, "http://localhost:" + tos.getAddress().getPort());
		jdbc.update("INSERT INTO connector_route (site_external_id, connector_name, http_status, outcome) "
				+ "VALUES (?, 'tos', 200, 'APPROVED')", SITE);

		String visitExternalId;
		try {
			// --- 1 · the instance that admits the truck, and then dies -------------
			try (ConfigurableApplicationContext first = boot(migrated, edge, false)) {
				AdmissionService admission = first.getBean(AdmissionService.class);

				AdmissionService.EventOutcome outcome = com.lynxis.orca.platform.scope.ScopeContext.callIn(
						com.lynxis.orca.platform.scope.Scope.of("site_external_id", java.util.Set.of(SITE)),
						() -> admission.accept(new InboundDeviceEvent("evt-" + UUID.randomUUID(), LANE,
								"DEV-IT-CAMERA", "lpr.capture",
								"{\"plate\":\"T-RESTART-01\"}", null)));

				visitExternalId = outcome.visitExternalId();
				assertThat(statusOf(jdbc, visitExternalId)).isEqualTo("ACTIVE");

				assertThat(count(jdbc, "SELECT COUNT(*) FROM ACT_RU_JOB"))
						.as("the first service task is a QUEUED JOB, not a call in flight. §B9: the "
								+ "lane lock is released at commit and no outbound call happens while "
								+ "holding it — which is also what makes this state survivable")
						.isPositive();
				assertThat(deviceCommands).isEmpty();

				log.info("§6 item 4 · visit {} admitted and left mid-flight; killing the instance",
						visitExternalId);
			}
			// The context is closed. Its engine, its executor, its pool: gone.

			assertThat(statusOf(jdbc, visitExternalId))
					.as("the visit outlives the process that started it, because it was never in "
							+ "that process to begin with")
					.isEqualTo("ACTIVE");

			// --- 2 · a DIFFERENT instance, which never saw the truck ---------------
			try (ConfigurableApplicationContext second = boot(migrated, edge, true)) {
				awaitStatus(jdbc, visitExternalId, "COMPLETED");

				assertThat(deviceCommands)
						.as("the second instance resumed from the step the first reached — the "
								+ "connector call — and carried the visit through to the barrier")
						.hasSize(1);
				assertThat(count(jdbc,
						"SELECT COUNT(*) FROM outbox WHERE event_type = 'visit.completed'"))
						.as("and the fact was recorded once, by whichever instance happened to "
								+ "finish it")
						.isEqualTo(1);
			}
		}
		finally {
			tos.stop(0);
			edge.stop(0);
			deviceCommands.clear();
		}
	}

	// ------------------------------------------------------------------------

	/**
	 * The shipping application, with its datasource pointed at the test schema and
	 * the async executor switched on or off.
	 *
	 * <p>{@code WebApplicationType.NONE}: this is about engine state, and a servlet
	 * container would only add a port to collide on.
	 */
	private static ConfigurableApplicationContext boot(DriverManagerDataSource dataSource,
			HttpServer edge, boolean asyncExecutor) {
		// COMMAND-LINE ARGUMENTS, not builder properties. `properties(...)` lands in
		// the defaultProperties source, which application.yaml overrides — so the
		// service would come up pointed at the committed localhost:1433 default and
		// fail with a connection error that says nothing about test wiring. Arguments
		// outrank the file, which is also how an operator overrides it.
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
						"--flowable.async-executor-activate=" + asyncExecutor);
	}

	private static void clean(JdbcTemplate jdbc) {
		for (String table : List.of("outbox_delivery", "outbox", "execution_event", "execution",
				"lane_session", "idempotency_record", "connector_route", "connector_config")) {
			jdbc.execute("DELETE FROM " + table);
		}
	}

	private static String statusOf(JdbcTemplate jdbc, String visitExternalId) {
		return jdbc.queryForObject("SELECT status FROM execution WHERE external_id = ?",
				String.class, visitExternalId);
	}

	private static void awaitStatus(JdbcTemplate jdbc, String visitExternalId, String expected) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
		String seen = null;
		while (System.nanoTime() < deadline) {
			seen = statusOf(jdbc, visitExternalId);
			if (expected.equals(seen)) {
				return;
			}
			sleep(250);
		}
		throw new AssertionError("visit " + visitExternalId + " never reached " + expected
				+ " after the restart; it is " + seen);
	}

	private static long count(JdbcTemplate jdbc, String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0 : counted;
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
		}
	}

	private static void publishTopologyLane() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT CAST(1 AS BIGINT) AS lane_id, "
				+ "''" + LANE + "'' AS lane_external_id, ''" + SITE + "'' AS site_external_id, "
				+ "''" + SITE + "'' AS site_code, CAST(1 AS BIT) AS site_is_primary, "
				+ "CAST(10 AS BIGINT) AS area_id, ''AREA-IT'' AS area_external_id, "
				+ "''AREA'' AS area_code, ''L01'' AS lane_code, N''Lane 1'' AS lane_name, "
				+ "CAST(1 AS INT) AS lane_priority, CAST(0 AS BIT) AS is_out_of_service')");
	}

	private static void grantTopologyLaneTo(String login) {
		admin("GRANT SELECT ON core.topology_lane TO [" + login + "]");
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
