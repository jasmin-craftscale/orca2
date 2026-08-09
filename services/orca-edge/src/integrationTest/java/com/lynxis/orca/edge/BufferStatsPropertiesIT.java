package com.lynxis.orca.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lynxis.orca.edge.domain.BufferStatsService;
import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;
import com.lynxis.orca.edge.domain.LaneOwnership;
import com.lynxis.orca.edge.persistence.EventBufferRepository;
import com.lynxis.orca.platform.lease.JdbcLeaseManager;
import com.lynxis.orca.platform.lease.LeaseManager;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;

/**
 * <strong>Proves the capture-buffer diagnostics answer an operator's real question.</strong>
 *
 * <p>The endpoint exists because an earlier review found that
 * <em>"`DEAD` events are recorded and visible only in the table."</em> An event
 * nobody could deliver is the single event a site operator most needs to see, and
 * until now seeing it required a database login.
 *
 * <p>So the properties here are not "the endpoint returns numbers". They are the
 * three readings an operator has to be able to tell apart, each written as the pair
 * that would be confused without the field that distinguishes them:
 *
 * <ul>
 *   <li>A <strong>severed link</strong> and a <strong>busy morning</strong> — same
 *       depth ordering, opposite conclusions, told apart only by age.</li>
 *   <li>A lane with <strong>an empty buffer</strong> and a lane that is
 *       <strong>missing from the answer</strong> — told apart only by reporting every
 *       lane the site publishes.</li>
 *   <li>A backlog <strong>this instance is draining</strong> and one it is
 *       <strong>not</strong> — told apart only by ownership.</li>
 * </ul>
 */
class BufferStatsPropertiesIT {

	private static final String SCHEMA = "edge";
	private static final String SITE = "SITE-IT";
	private static final String BUSY_LANE = "LANE-IT-01";
	private static final String QUIET_LANE = "LANE-IT-02";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private ScopeSeam seam;
	private EventBufferRepository buffer;
	private LeaseManager leaseManager;

	@BeforeAll
	static void migrate() {
		// The view itself is published per test, and it carries its own grant — a
		// DROP VIEW takes the grant with it, so granting once here would work exactly
		// until the first republish.
		dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		seam = new JdbcScopeSeam(jdbc);
		buffer = new EventBufferRepository(seam);
		leaseManager = new JdbcLeaseManager(jdbc, "orca-edge");

		// ⚠️ TWO lanes, and republished per test rather than once per class. The edge
		// suites share one integration database and one `core` schema, so the view is
		// whatever the last writer made it — publishing here means this suite sees its
		// own two lanes whichever suite ran before it, and the other suites republish
		// their own in their own setup. Same columns, deliberately (see the fixture).
		publishTwoLanes();

		jdbc.execute("DELETE FROM event_buffer");
		jdbc.execute("DELETE FROM service_lease");
	}

	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a DEAD event is visible on the endpoint — the gap this closes")
	void deadEventsAreVisibleWithoutADatabaseLogin() {
		inScope(() -> {
			append(BUSY_LANE, "evt-dead-1");
			append(BUSY_LANE, "evt-dead-2");
			append(BUSY_LANE, "evt-alive");
		});
		// Two of them exhausted their attempts and were retired. Failed records are retained
		// rather than removing them precisely so this stays true.
		jdbc.update("UPDATE event_buffer SET status = 'DEAD', last_error = 'link never returned' "
				+ "WHERE event_uuid IN ('evt-dead-1', 'evt-dead-2')");

		BufferStatsService.Stats stats = read("instance-a");

		assertThat(stats.totalDead())
				.as("""
						THE POINT OF THIS TEST. Before H3 this number existed only in a table an \
						operator cannot reach, so "the truck went through and nothing happened" had \
						no answer short of a database login.""")
				.isEqualTo(2);
		assertThat(lane(stats, BUSY_LANE).dead()).isEqualTo(2);
		assertThat(lane(stats, BUSY_LANE).depth())
				.as("DEAD is not depth — a retired event is not still queued, and reporting it as "
						+ "backlog would tell an operator to wait for something nothing will retry")
				.isEqualTo(1);
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a severed link and a busy morning have different ages, and depth alone cannot tell them apart")
	void ageDistinguishesAnOutageFromTraffic() {
		inScope(() -> {
			// The severed link: few events, all of them old.
			append(BUSY_LANE, "evt-old-1");
			append(BUSY_LANE, "evt-old-2");
		});
		jdbc.update("UPDATE event_buffer SET received_at = DATEADD(hour, -9, SYSUTCDATETIME())");

		BufferStatsService.LaneStats severed = lane(read("instance-a"), BUSY_LANE);

		assertThat(severed.depth()).isEqualTo(2);
		assertThat(severed.oldestUndeliveredAgeSeconds())
				.as("""
						A depth of 2 that is nine hours old is an outage; a depth of 2 that is two \
						seconds old is traffic. They sort identically on depth, and an endpoint that \
						reported only depth would invite the wrong conclusion from the right number.""")
				.isNotNull()
				.isGreaterThan(Duration.ofHours(8).toSeconds());

		// The busy morning: more events, all of them recent.
		jdbc.execute("DELETE FROM event_buffer");
		inScope(() -> {
			for (int i = 0; i < 20; i++) {
				append(BUSY_LANE, "evt-fresh-" + i);
			}
		});

		BufferStatsService.LaneStats busy = lane(read("instance-a"), BUSY_LANE);

		assertThat(busy.depth()).as("ten times the backlog of the outage above").isEqualTo(20);
		assertThat(busy.oldestUndeliveredAgeSeconds())
				.as("and none of it old. The larger number is the healthier lane")
				.isNotNull()
				.isLessThan(Duration.ofMinutes(5).toSeconds());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("the oldest undelivered event is the oldest by SEQUENCE, not by clock")
	void theOldestIsTakenFromTheBuffersOwnOrder() {
		inScope(() -> {
			append(BUSY_LANE, "evt-first");
			append(BUSY_LANE, "evt-second");
		});
		// Same millisecond, which is ordinary at a busy lane and is exactly why
		// sequence_no exists. Ordering by the clock would make "the oldest" ambiguous
		// at precisely the moment the endpoint is being read.
		jdbc.update("UPDATE event_buffer SET received_at = '2026-08-07T09:00:00.000'");
		jdbc.update("UPDATE event_buffer SET status = 'ACKED' WHERE event_uuid = 'evt-first'");

		BufferStatsService.LaneStats stats = lane(read("instance-a"), BUSY_LANE);

		assertThat(stats.depth()).isEqualTo(1);
		assertThat(stats.oldestUndeliveredAt())
				.as("the acknowledged row is not the oldest UNDELIVERED one, however early it arrived")
				.isNotNull();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a lane with an empty buffer is REPORTED as empty, never omitted")
	void everyLaneTheSitePublishesAppears() {
		inScope(() -> append(BUSY_LANE, "evt-only"));

		BufferStatsService.Stats stats = read("instance-a");

		assertThat(stats.lanes()).extracting(BufferStatsService.LaneStats::laneExternalId)
				.as("a lane missing from the answer is indistinguishable from a lane with nothing in "
						+ "it, and those are very different answers to 'why did nothing happen'")
				.containsExactlyInAnyOrder(BUSY_LANE, QUIET_LANE);

		assertThat(lane(stats, QUIET_LANE).depth()).isZero();
		assertThat(lane(stats, QUIET_LANE).oldestUndeliveredAt())
				.as("absent rather than zero: an empty buffer has no oldest event, and reporting an "
						+ "age of 0 would read as 'something arrived just now'")
				.isNull();
		assertThat(lane(stats, QUIET_LANE).oldestUndeliveredAgeSeconds()).isNull();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("the same backlog reads as owned on one instance and not owned on the other")
	void ownershipIsOnTheAnswerBecauseTheNumbersMeanDifferentThings() {
		inScope(() -> append(BUSY_LANE, "evt-1"));

		// instance-a takes both lanes; instance-b then finds them held.
		BufferStatsService.Stats owner = read("instance-a");
		BufferStatsService.Stats bystander = read("instance-b");

		assertThat(lane(owner, BUSY_LANE).ownedByThisInstance()).isTrue();
		assertThat(lane(bystander, BUSY_LANE).ownedByThisInstance())
				.as("§C3 gives one instance ownership of a lane at a time. Without this field the "
						+ "same depth means 'we are behind and draining' on one host and 'we are "
						+ "behind and nothing here is draining' on the other, with nothing on the "
						+ "response saying which")
				.isFalse();

		assertThat(lane(bystander, BUSY_LANE).depth())
				.as("the backlog itself is the same number from both — it is one buffer")
				.isEqualTo(lane(owner, BUSY_LANE).depth());
	}

	// ------------------------------------------------------------------------

	/** Reads the endpoint's service as one instance would, taking its leases first. */
	private BufferStatsService.Stats read(String holderId) {
		LaneOwnership ownership =
				new LaneOwnership(leaseManager, seam, holderId, Duration.ofSeconds(30), SITE);
		BufferStatsService stats = new BufferStatsService(buffer, ownership, SITE);
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(SITE)), () -> {
			ownership.reconcile();
			return stats.read();
		});
	}

	private static BufferStatsService.LaneStats lane(BufferStatsService.Stats stats, String lane) {
		return stats.lanes().stream()
				.filter(candidate -> candidate.laneExternalId().equals(lane))
				.findFirst()
				.orElseThrow(() -> new AssertionError("lane " + lane + " is missing from the answer"));
	}

	private void append(String lane, String eventUuid) {
		buffer.append(new BufferedEvent(0, eventUuid, SITE, lane, "DEV-IT", "PLATE_READ",
				"<ZapPacket/>", "{}", BufferedEvent.PENDING, 0, null, null, null, null));
	}

	private void inScope(Runnable work) {
		ScopeContext.runIn(Scope.of("site_external_id", Set.of(SITE)), work);
	}

	/**
	 * Two lanes in core's published view, with the columns every edge suite uses.
	 *
	 * <p>The shared shape matters more than the row count — see
	 * {@code DeviceCommandPropertiesIT.EdgeTopologyFixture}. Two rows are needed here
	 * because "a lane with nothing in it is still reported" cannot be tested with one.
	 */
	private static void publishTwoLanes() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS "
				+ "SELECT CAST(1 AS BIGINT) AS lane_id, ''" + SITE + "'' AS site_external_id, "
				+ "''" + BUSY_LANE + "'' AS lane_external_id, ''http://localhost:1'' AS device_host_url "
				+ "UNION ALL "
				+ "SELECT CAST(2 AS BIGINT), ''" + SITE + "'', ''" + QUIET_LANE + "'', "
				+ "''http://localhost:1''')");
		DeviceCommandPropertiesIT.EdgeTopologyFixture.grantTo("it_" + SCHEMA);
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
