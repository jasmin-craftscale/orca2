package com.lynxis.orca.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.edge.domain.DeviceCommandService;
import com.lynxis.orca.edge.domain.DeviceHostPort;
import com.lynxis.orca.edge.domain.EdgeTables.CommandLogEntry;
import com.lynxis.orca.edge.domain.RestDeviceHost;
import com.lynxis.orca.edge.persistence.CommandLogRepository;
import com.lynxis.orca.platform.idempotency.IdempotencyStore;
import com.lynxis.orca.platform.idempotency.JdbcIdempotencyStore;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;

import com.sun.net.httpserver.HttpServer;

/**
 * <strong>WP7 · the two properties a command that moves a barrier has to hold.</strong>
 *
 * <p>§6 items 7 and 8 of the phase plan, and both are about the physical world:
 *
 * <ul>
 *   <li><em>Replay every command type with the same {@code command_id}</em> → the
 *       recorded outcome, byte for byte, never a bare duplicate. A barrier does not
 *       rise twice because a network dropped a response.</li>
 *   <li><em>Deliver a command with {@code deadline_ms} already elapsed</em> →
 *       discarded, and the device host records <strong>zero</strong> calls. Stale
 *       actuation is dangerous in a way a missed command is not: a barrier that
 *       rises for nobody is a barrier that can come down on somebody.</li>
 * </ul>
 *
 * <p>The last test is the one that could not be written against a fake: it runs
 * {@link RestDeviceHost} against a real HTTP server that accepts the request and
 * then goes quiet, because the {@code FAILED}/{@code UNKNOWN} distinction lives
 * entirely in what the transport does and nowhere in the code's structure.
 */
class DeviceCommandPropertiesIT {

	private static final Logger log = LoggerFactory.getLogger(DeviceCommandPropertiesIT.class);

	private static final String SCHEMA = "edge";
	private static final String SITE = "SITE-IT";
	private static final String LANE = "LANE-IT-01";

	/** §C3's action vocabulary, in full. Item 7 says "every command type". */
	private static final List<String> EVERY_ACTION =
			List.of("RAISE_GATE", "LOWER_GATE", "PRINT", "SET_IO", "PTZ_PRESET");

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private CommandLogRepository commandLog;
	private IdempotencyStore idempotency;
	private RecordingDeviceHost deviceHost;
	private DeviceCommandService commands;

	@BeforeAll
	static void migrate() {
		dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		EdgeTopologyFixture.publishTopologyLane(SITE, LANE, "http://localhost:1");
		EdgeTopologyFixture.grantTo("it_" + SCHEMA);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		commandLog = new CommandLogRepository(new JdbcScopeSeam(jdbc));
		idempotency = new JdbcIdempotencyStore(jdbc);
		deviceHost = new RecordingDeviceHost();
		commands = new DeviceCommandService(commandLog, deviceHost, idempotency, SITE, "instance-a");
		jdbc.execute("DELETE FROM command_log");
		jdbc.execute("DELETE FROM idempotency_record");
	}

	// ------------------------------------------------------------------------
	// §6 item 7 — replay every command type.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("replaying any command type returns the RECORDED outcome and never acts twice")
	void replayingACommandReturnsTheRecordedOutcomeAndDoesNotActAgain() {
		inScope(() -> {
			for (String action : EVERY_ACTION) {
				String commandId = "cmd-" + action + "-" + UUID.randomUUID();
				deviceHost.answer = DeviceHostPort.Outcome.executed(
						"{\"status\":\"OK\",\"action\":\"" + action + "\"}");

				DeviceCommandService.Result first = commands.issue(command(commandId, action), Instant.now());
				assertThat(first.status()).isEqualTo(CommandLogEntry.EXECUTED);

				// The caller lost the answer and asks again. A rejection is the one
				// response it cannot use — it retried BECAUSE it never saw the first.
				DeviceCommandService.Result replay = commands.issue(command(commandId, action), Instant.now());

				assertThat(replay.status()).isEqualTo(first.status());
				assertThat(replay.deviceResponse())
						.as("the DEVICE's own words, byte for byte — not the platform's summary of them")
						.isEqualTo(first.deviceResponse());
			}
		});

		assertThat(deviceHost.calls)
				.as("five actions, five calls. A sixth would be a barrier moving because a caller "
						+ "retried")
				.hasSize(EVERY_ACTION.size());
		assertThat(count("SELECT COUNT(*) FROM command_log")).isEqualTo(EVERY_ACTION.size());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a recorded FAILURE replays as that failure — it is an outcome, not a reason to retry")
	void aRecordedFailureReplaysAsTheSameFailure() {
		String commandId = "cmd-" + UUID.randomUUID();
		deviceHost.answer = DeviceHostPort.Outcome.failed("{\"status\":\"ERROR\"}", "the host refused");

		inScope(() -> {
			assertThat(commands.issue(command(commandId, "RAISE_GATE"), Instant.now()).status())
					.isEqualTo(CommandLogEntry.FAILED);

			// A recorded failure is still a RECORDED OUTCOME. Re-running it would be
			// the store deciding, on the caller's behalf, that the failure was
			// transient — which is a decision about a barrier.
			DeviceCommandService.Result replay = commands.issue(command(commandId, "RAISE_GATE"), Instant.now());
			assertThat(replay.status()).isEqualTo(CommandLogEntry.FAILED);
		});

		assertThat(deviceHost.calls).hasSize(1);
	}

	// ------------------------------------------------------------------------
	// §6 item 8 — the elapsed deadline.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a command whose deadline has already elapsed is DISCARDED — the device host sees zero calls")
	void anExpiredCommandIsDiscardedAndTheDeviceHostIsNeverCalled() {
		String commandId = "cmd-expired-" + UUID.randomUUID();

		inScope(() -> {
			// Issued five seconds ago with a one-second deadline. The truck may have
			// gone; another may be in the lane.
			DeviceCommandService.Result result = commands.issue(
					new DeviceCommandService.Command(commandId, LANE, "DEV-IT-BARRIER", "RAISE_GATE",
							null, 1_000L),
					Instant.now().minusSeconds(5));

			assertThat(result.status())
					.as("FAILED and not UNKNOWN: UNKNOWN means nobody knows whether the device "
							+ "acted, and here everybody knows — nothing was sent")
					.isEqualTo(CommandLogEntry.FAILED);
			assertThat(result.detail())
					.as("the distinction from a host that refused lives in the detail, because both "
							+ "are FAILED and only one of them reached the hardware")
					.contains("discarded as expired");
		});

		assertThat(deviceHost.calls)
				.as("THE POINT OF THIS TEST: zero calls. A barrier that rises for nobody is a "
						+ "barrier that can come down on somebody")
				.isEmpty();

		// Discarded, and RECORDED as discarded — an operator asking why the barrier
		// did not rise gets an answer rather than a silence.
		assertThat(jdbc.queryForObject("SELECT detail FROM command_log WHERE command_id = ?",
				String.class, commandId)).contains("Nothing was sent");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("an expired command replays as the same discard, and still never reaches the device")
	void replayingAnExpiredCommandStillSendsNothing() {
		String commandId = "cmd-expired-" + UUID.randomUUID();
		DeviceCommandService.Command expired = new DeviceCommandService.Command(
				commandId, LANE, "DEV-IT-BARRIER", "RAISE_GATE", null, 1_000L);

		inScope(() -> {
			commands.issue(expired, Instant.now().minusSeconds(5));
			// A retry from a caller that never saw the discard. It must not become a
			// fresh command with a fresh clock — which is what a bare "duplicate"
			// answer would push a caller into doing.
			DeviceCommandService.Result replay = commands.issue(expired, Instant.now());
			assertThat(replay.status()).isEqualTo(CommandLogEntry.FAILED);
			assertThat(replay.detail()).contains("discarded as expired");
		});

		assertThat(deviceHost.calls).isEmpty();
	}

	// ------------------------------------------------------------------------
	// The transport, against a real socket. FAILED and UNKNOWN are not the same.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a device host that goes quiet is UNKNOWN, not FAILED — §B10's whole point")
	void aSilentDeviceHostProducesUnknown() throws Exception {
		HttpServer host = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		host.createContext("/", exchange -> {
			// Accepts the request and never answers. The barrier may be rising right
			// now, and nothing in this process can know.
			sleep(3_000);
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});
		host.start();

		try {
			DeviceHostPort.Outcome outcome = new RestDeviceHost().issue(
					"http://localhost:" + host.getAddress().getPort(),
					new DeviceHostPort.HostCommand("cmd-silent", "DEV", "RAISE_GATE", null, 500L));

			assertThat(outcome.status())
					.as("""
							A client that coerces this to FAILED tells the gate that a barrier did \
							not rise when it may well have. §B10 resolves an unknown outcome by \
							VERIFYING the device — never by retrying and never by assuming.""")
					.isEqualTo(DeviceHostPort.Outcome.UNKNOWN);
		}
		finally {
			host.stop(0);
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a device host that answers with an error is FAILED — knowable, quite unlike a silence")
	void aRefusingDeviceHostProducesFailed() throws Exception {
		HttpServer host = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		host.createContext("/", exchange -> {
			byte[] body = "{\"status\":\"ERROR\",\"detail\":\"barrier is padlocked\"}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(503, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		host.start();

		try {
			DeviceHostPort.Outcome outcome = new RestDeviceHost().issue(
					"http://localhost:" + host.getAddress().getPort(),
					new DeviceHostPort.HostCommand("cmd-refused", "DEV", "RAISE_GATE", null, 5_000L));

			assertThat(outcome.status()).isEqualTo(DeviceHostPort.Outcome.FAILED);
			assertThat(outcome.deviceResponse())
					.as("the host's own words are recorded, because after an incident the platform's "
							+ "reading of the answer is worth less than the answer")
					.contains("padlocked");
		}
		finally {
			host.stop(0);
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a 200 whose body does not decode is FAILED — an acknowledgement is not a confirmation")
	void anUndecodableAnswerIsNotAnExecution() throws Exception {
		HttpServer host = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		host.createContext("/", exchange -> {
			byte[] body = "<html>proxy interposed</html>".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		host.start();

		try {
			// §C3: a command completes when the device host confirms it ACTED — an
			// acknowledgement AND a body that decodes. Reading an unrecognised body
			// optimistically is how "the barrier rose" comes to mean "the barrier was
			// asked to".
			assertThat(new RestDeviceHost().issue(
					"http://localhost:" + host.getAddress().getPort(),
					new DeviceHostPort.HostCommand("cmd-html", "DEV", "RAISE_GATE", null, 5_000L))
					.status())
					.isEqualTo(DeviceHostPort.Outcome.FAILED);
		}
		finally {
			host.stop(0);
		}
	}

	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a lane with no device host is refused, and the command stays claimable")
	void aLaneWithNoDeviceHostIsRefusedAndReleasable() {
		String commandId = "cmd-" + UUID.randomUUID();

		inScope(() -> assertThatThrownBy(() -> commands.issue(
				new DeviceCommandService.Command(commandId, "LANE-NOT-OURS", null, "RAISE_GATE", null, 5_000L),
				Instant.now()))
				.isInstanceOf(DeviceCommandService.LaneHasNoDeviceHostException.class));

		// Nothing reached the outside world, so the claim was RELEASED rather than
		// recorded — the command is worth attempting again once the lane is
		// configured. Once an actuating call has been made, release is the wrong
		// tool and the code does not use it there.
		assertThat(count("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = '" + commandId + "'"))
				.as("a released claim leaves nothing behind, so a corrected configuration can be "
						+ "retried without a new command id")
				.isZero();
	}

	// ------------------------------------------------------------------------

	private static DeviceCommandService.Command command(String commandId, String action) {
		return new DeviceCommandService.Command(commandId, LANE, "DEV-IT-BARRIER", action, null, 5_000L);
	}

	private void inScope(Runnable work) {
		ScopeContext.runIn(Scope.of("site_external_id", Set.of(SITE)), work);
	}

	private long count(String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0 : counted;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	/** Counts what actually reached the hardware. The assertion that matters is on this list. */
	private static final class RecordingDeviceHost implements DeviceHostPort {

		private final List<HostCommand> calls = new CopyOnWriteArrayList<>();

		private Outcome answer = Outcome.executed("{\"status\":\"OK\"}");

		@Override
		public Outcome issue(String deviceHostUrl, HostCommand command) {
			calls.add(command);
			log.debug("device host called: {} {}", command.action(), command.commandId());
			return answer;
		}
	}

	/** core's published view, standing in for orca-core having migrated. Shared shape — see the class. */
	static final class EdgeTopologyFixture {

		private EdgeTopologyFixture() {
		}

		/**
		 * ⚠️ Every edge suite creates this view with the SAME columns, deliberately.
		 * They share one integration database and one {@code core} schema, so the
		 * suite that ran last is the one whose definition survives — and two
		 * definitions would make the pair pass or fail by test ordering.
		 */
		static void publishTopologyLane(String site, String lane, String deviceHostUrl) {
			admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
			admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
			admin("EXEC('CREATE VIEW core.topology_lane AS SELECT CAST(1 AS BIGINT) AS lane_id, "
					+ "''" + site + "'' AS site_external_id, ''" + lane + "'' AS lane_external_id, "
					+ "''" + deviceHostUrl + "'' AS device_host_url')");
		}

		static void grantTo(String login) {
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
}
