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
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.api.generated.model.DeviceEvent;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventBatch;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventBatchEnvelope;
import com.lynxis.orca.runtime.execution.domain.VisitCompletion;

import com.sun.net.httpserver.HttpServer;

/**
 * <strong>Runs one truck end to end and proves the transaction that closes it.</strong>
 *
 * <p>Nothing inside the platform is stubbed: a
 * device event arrives over HTTP, admission starts {@code gate-visit}, the real
 * {@link com.lynxis.orca.runtime.integration.domain.RestConnector} calls a real
 * socket, the real {@link com.lynxis.orca.runtime.execution.persistence.EdgeDeviceCommandClient}
 * calls another, and the visit closes with its fact recorded. The two things
 * outside the platform — a Terminal Operating System and orca-edge — are real HTTP
 * servers rather than substituted beans, because the connector's status routing,
 * its deadline and the {@code FAILED}/{@code UNKNOWN} distinction all live in what
 * a socket does.
 *
 * <p><strong>The test that matters most is
 * {@link #neitherTheVisitNorItsFactSurvivesAloneWhenTheWriteFails()}.</strong>
 * "The outbox writes a row" is not a test; "killing the process between the two
 * writes leaves neither" is.
 */
@SpringBootTest(
		classes = RuntimeApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + VisitLifecycleIT.SCHEMA,
				"spring.flyway.schemas=" + VisitLifecycleIT.SCHEMA,
				"spring.flyway.default-schema=" + VisitLifecycleIT.SCHEMA,
				"orca.required-views=",
				// ON. Both service tasks are flowable:async="true" precisely so that no
				// outbound call happens inside admission's transaction, which means
				// nothing advances at all without a worker.
				"flowable.async-executor-activate=true",
				"orca.installation.site-external-id=" + VisitLifecycleIT.SITE,
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
				// The relay runs, and has nothing to deliver: no consumer is registered.
				// That is the current on-site state and the fact is still recorded.
				"orca.outbox.relay.interval=500ms",
		})
// Same reason as GateVisitProcessIT: this suite runs the async executor, and every
// runtime suite migrates the SAME `runtime` schema because V100 stamps that name.
// Spring caches a context across test classes, so without this the executor would
// still be picking up jobs — and calling the fakes stopped in @AfterAll — while a
// later suite rebuilt the tables underneath it.
@org.springframework.test.annotation.DirtiesContext(
		classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class VisitLifecycleIT {

	static final String SCHEMA = "runtime";
	static final String SITE = "SITE-IT";
	static final String LANE = "LANE-IT-01";

	private static final Logger log = LoggerFactory.getLogger(VisitLifecycleIT.class);

	private static final String CREDENTIAL = "integration-test-credential-not-a-fixture";

	private static HttpServer tos;
	private static HttpServer edge;

	/** What each fake answered, so a test can assert on what actually crossed the wire. */
	private static final AtomicInteger tosStatus = new AtomicInteger(200);
	private static final AtomicInteger tosDelayMillis = new AtomicInteger(0);
	private static final List<String> deviceCommands = new CopyOnWriteArrayList<>();
	private static final java.util.concurrent.atomic.AtomicReference<String> deviceOutcome =
			new java.util.concurrent.atomic.AtomicReference<>("EXECUTED");

	@LocalServerPort
	private int port;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private com.lynxis.orca.runtime.workitem.domain.WorkItemService workItems;

	private JdbcTemplate jdbc;
	private RestClient runtime;

	@DynamicPropertySource
	static void wireEverything(DynamicPropertyRegistry registry) {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);

		publishTopologyLane();
		grantTopologyLaneTo("it_" + SCHEMA);
		publishRoutingTopology();

		tos = start("/tos/v1/visits", exchange -> {
			sleep(tosDelayMillis.get());
			respond(exchange, tosStatus.get(), "{\"decision\":\"stub\"}");
		});
		edge = start("/internal/commands/v1", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			deviceCommands.add(body);
			// Edge's own envelope, exactly as DeviceCommandController produces it.
			respond(exchange, 200, "{\"status\":\"SUCCESS\",\"code\":\"OK\",\"data\":{"
					+ "\"commandId\":\"x\",\"status\":\"" + deviceOutcome.get() + "\","
					+ "\"deviceResponse\":\"{\\\"status\\\":\\\"OK\\\"}\"}}");
		});

		registry.add("orca.runtime.edge-base-url", () -> "http://localhost:" + edge.getAddress().getPort());
	}

	@AfterAll
	static void stopFakes() {
		if (tos != null) {
			tos.stop(0);
		}
		if (edge != null) {
			edge.stop(0);
		}
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DELETE FROM outbox_delivery");
		jdbc.execute("DELETE FROM outbox");
		jdbc.execute("DELETE FROM work_item_audit");
		jdbc.execute("DELETE FROM work_item");
		jdbc.execute("DELETE FROM execution_event");
		jdbc.execute("DELETE FROM execution");
		jdbc.execute("DELETE FROM lane_session");
		jdbc.execute("DELETE FROM idempotency_record");
		jdbc.execute("DELETE FROM connector_route");
		jdbc.execute("DELETE FROM connector_config");

		// Connector configuration is data rather than code: the endpoint, the
		// deadline and the status routing are all rows.
		jdbc.update("INSERT INTO connector_config (site_external_id, connector_name, base_url, "
						+ "request_path, deadline_ms, is_enabled) VALUES (?, 'tos', ?, '/tos/v1/visits', ?, 1)",
				SITE, "http://localhost:" + tos.getAddress().getPort(), 4000);
		jdbc.update("INSERT INTO connector_route (site_external_id, connector_name, http_status, outcome) "
				+ "VALUES (?, 'tos', 200, 'APPROVED')", SITE);

		tosStatus.set(200);
		tosDelayMillis.set(0);
		deviceOutcome.set("EXECUTED");
		deviceCommands.clear();

		runtime = RestClient.builder()
				.baseUrl("http://localhost:" + port)
				.defaultHeader("X-Orca-Internal-Auth", CREDENTIAL)
				.defaultHeader("X-Orca-Service", "orca-edge")
				.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("plate read in → TOS approves → the barrier is commanded → the visit completes with its fact")
	void oneTruckFromPlateReadToRecordedFact() {
		String visit = admit();

		awaitStatus(visit, "COMPLETED");

		assertThat(deviceCommands)
				.as("the barrier was commanded exactly once, over §C3's contract")
				.hasSize(1);
		assertThat(deviceCommands.getFirst())
				.contains("\"action\":\"RAISE_GATE\"")
				.contains("\"laneExternalId\":\"" + LANE + "\"")
				// H1: the device host addresses the device in the URL path, so a command
				// that does not name one cannot be sent. This assertion is what stops the
				// field being quietly dropped again.
				.contains("\"deviceExternalId\":\"DEV-DEMO-BARRIER\"");

		// The fact is recorded in the visit's own transaction. Saving the thing and then telling
		// somebody fails in two directions and neither is detectable.
		assertThat(jdbc.queryForList(
				"SELECT ordering_key, event_type, payload FROM outbox ORDER BY publish_seq"))
				.singleElement()
				.satisfies(row -> {
					assertThat(row.get("event_type")).isEqualTo(VisitCompletion.VISIT_COMPLETED);
					assertThat(row.get("ordering_key"))
							.as("§D3 orders facts PER KEY, never globally — one lane's visits are "
									+ "sequenced and no other lane waits on them")
							.isEqualTo("lane:" + LANE);
					assertThat(row.get("payload").toString()).contains(visit).contains("T-DEMO");
				});

		// ⚠️ Zero delivery rows is the current on-site state rather than a
		// bug: orca.outbox.consumers is empty because nothing on-site consumes this.
		assertThat(count("SELECT COUNT(*) FROM outbox_delivery"))
				.as("the fact is RECORDED; delivery arrives with a destination")
				.isZero();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a status nobody mapped queues a WORK ITEM, the barrier is NOT commanded — and completing it closes the visit")
	void anUnmappedConnectorStatusReachesAHumanAndCommandsNothing() {
		// 409 has no connector_route row, so the outcome token is HTTP_409, which no
		// branch matches — and gate-visit's default flow takes it to a human. Since
		// "To a human" means the process PARKS and a work item queues in
		// the same transaction; the visit stays ACTIVE, because the truck is still
		// physically standing at the gate.
		tosStatus.set(409);

		String visit = admit();
		String workItem = awaitQueuedWorkItem(visit);
		assertThat(statusOf(visit)).isEqualTo("ACTIVE");

		assertThat(deviceCommands)
				.as("an answer nobody wrote a branch for must never become an implicit approval")
				.isEmpty();
		assertThat(count("SELECT COUNT(*) FROM outbox"))
				.as("and no fact is published for a visit that did not complete")
				.isZero();

		// The clerk resolves it: claim, then complete — and complete ADVANCES the
		// parked process in the same transaction, so by the time the call returns
		// the visit is already closed. No awaiting: synchronous is the claim.
		com.lynxis.orca.platform.scope.ScopeContext.runIn(
				com.lynxis.orca.platform.scope.Scope.of("site_external_id", java.util.Set.of(SITE)),
				() -> {
					workItems.take(workItem, "op-clerk");
					workItems.complete(workItem, "op-clerk", "{\"decision\":\"let through\"}");
				});
		assertThat(statusOf(visit))
				.as("complete-and-advance is ONE transaction; the visit closed before complete() returned")
				.isEqualTo("MANUAL");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a customer system slower than its deadline queues a work item rather than holding the lane")
	void aConnectorSlowerThanItsDeadlineReachesAHuman() {
		// The connector's deadline is 4 s in configuration; this answers in 6.
		// Every external call has a deadline AND a defined outcome when it is
		// exceeded. Here that outcome is routable rather than a hung worker.
		tosDelayMillis.set(6_000);

		String visit = admit();
		awaitQueuedWorkItem(visit);
		assertThat(statusOf(visit)).isEqualTo("ACTIVE");

		assertThat(deviceCommands).isEmpty();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a device outcome of UNKNOWN queues a work item, and the barrier is commanded exactly ONCE")
	void anUnknownDeviceOutcomeIsNeverRetried() {
		deviceOutcome.set("UNKNOWN");

		String visit = admit();
		awaitQueuedWorkItem(visit);

		assertThat(deviceCommands)
				.as("§B10: an unknown outcome is resolved by LOOKING, never by retrying blindly. "
						+ "A second command here would be a barrier moved on a guess")
				.hasSize(1);
	}

	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 15, unit = TimeUnit.MINUTES)
	@DisplayName("NEITHER the completion nor its fact survives alone when the second write fails")
	void neitherTheVisitNorItsFactSurvivesAloneWhenTheWriteFails() {
		// The canonical property of this whole programme, run rather than asserted in
		// prose: the visit row and the outbox row commit together or not at all.
		//
		// The outbox table is taken away, so the fact CANNOT be written. If the two
		// were separate transactions, the visit would close and no fact would exist —
		// and nothing anywhere would report it.
		hideOutboxTable();
		String visit;
		try {
			visit = admit();
			// Long enough for the async executor to run the whole process and exhaust
			// the completion job's retries.
			sleep(20_000);

			assertThat(statusOf(visit))
					.as("""
							The process reached its end event and the platform could not record the \
							fact. The visit therefore stays ACTIVE — visible, and obviously unfinished \
							— rather than closing with a fact nobody has. A visit that closed here \
							would be a truck the cloud tier is never told about, with nothing to \
							detect it.""")
					.isEqualTo("ACTIVE");
		}
		finally {
			restoreOutboxTable();
		}

		// And when the table comes back, the retry closes it AND records the fact.
		// Neither half was lost; they were waiting for each other.
		awaitStatus(visit, "COMPLETED");
		assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'visit.completed'"))
				.as("exactly one fact, after however many failed attempts — the guarded update is "
						+ "what makes the retry idempotent")
				.isEqualTo(1);
	}

	// ------------------------------------------------------------------------

	private String admit() {
		DeviceEventBatchEnvelope answer = runtime.post()
				.uri("/internal/events/v1")
				.body(new DeviceEventBatch().events(List.of(new DeviceEvent()
						.eventUuid("evt-" + UUID.randomUUID())
						.laneExternalId(LANE)
						.deviceExternalId("DEV-IT-CAMERA")
						.eventType("lpr.capture")
						.attributes("{\"plate\":\"T-DEMO-01\",\"confidence\":\"0.94\"}"))))
				.retrieve()
				.body(DeviceEventBatchEnvelope.class);

		assertThat(answer).isNotNull();
		return answer.getData().getResults().getFirst().getVisitExternalId();
	}

	private void awaitStatus(String visitExternalId, String expected) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
		String seen = null;
		while (System.nanoTime() < deadline) {
			seen = statusOf(visitExternalId);
			if (expected.equals(seen)) {
				return;
			}
			sleep(200);
		}
		throw new AssertionError("visit " + visitExternalId + " never reached " + expected
				+ "; it is " + seen);
	}

	/**
	 * Waits for the engine to park and the creation listener to queue the item —
	 * they commit together, so seeing the item means the wait state exists too.
	 */
	private String awaitQueuedWorkItem(String visitExternalId) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
		while (System.nanoTime() < deadline) {
			List<String> queued = jdbc.queryForList(
					"SELECT external_id FROM work_item WHERE visit_external_id = ? AND status = 'QUEUED'",
					String.class, visitExternalId);
			if (!queued.isEmpty()) {
				return queued.getFirst();
			}
			sleep(250);
		}
		throw new AssertionError("no QUEUED work item ever appeared for visit " + visitExternalId);
	}

	private String statusOf(String visitExternalId) {
		return jdbc.queryForObject("SELECT status FROM execution WHERE external_id = ?",
				String.class, visitExternalId);
	}

	private long count(String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0 : counted;
	}

	/** Renames rather than drops, so the foreign key from outbox_delivery survives with it. */
	private void hideOutboxTable() {
		jdbc.execute("EXEC sp_rename '" + SCHEMA + ".outbox', 'outbox_taken_away'");
	}

	private void restoreOutboxTable() {
		jdbc.execute("EXEC sp_rename '" + SCHEMA + ".outbox_taken_away', 'outbox'");
	}

	// ------------------------------------------------------------------------

	private static HttpServer start(String path, com.sun.net.httpserver.HttpHandler handler) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext(path, handler);
			server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			log.info("fake listening on {} at port {}", path, server.getAddress().getPort());
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
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static void publishRoutingTopology() {
		com.lynxis.orca.runtime.workitem.RoutingTopologyFixture.publish(
				VisitLifecycleIT::admin, "it_" + SCHEMA);
	}

	private static void publishTopologyLane() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT CAST(1 AS BIGINT) AS lane_id, "
				+ "''" + LANE + "'' AS lane_external_id, ''" + SITE + "'' AS site_external_id')");
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
