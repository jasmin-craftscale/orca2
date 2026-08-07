package com.lynxis.orca.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.edge.domain.DeliveryPump;
import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;
import com.lynxis.orca.edge.domain.LaneOwnership;
import com.lynxis.orca.edge.persistence.EventBufferRepository;
import com.lynxis.orca.platform.lease.FencedWrite;
import com.lynxis.orca.platform.lease.JdbcLeaseManager;
import com.lynxis.orca.platform.lease.Lease;
import com.lynxis.orca.platform.lease.LeaseManager;
import com.lynxis.orca.platform.lease.StaleFenceTokenException;
import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;

/**
 * <strong>WP5 · the two properties edge exists to hold.</strong>
 *
 * <p>§B10, "nothing in flight is lost": <em>sever the link under load, assert zero
 * loss and preserved order.</em> And "exactly one of the things that must be one":
 * <em>one owner of a lane's hardware — expire the lease mid-write, assert the write
 * is rejected.</em>
 *
 * <p>Both are about what happens when something goes wrong, so both are written as
 * failures rather than as flows. "The buffer stores an event" is not a test.
 */
class EdgeIngestPropertiesIT {

	private static final Logger log = LoggerFactory.getLogger(EdgeIngestPropertiesIT.class);

	private static final String SCHEMA = "edge";
	private static final String SITE = "SITE-IT";
	private static final String LANE = "LANE-IT-01";

	private static DataSource dataSource;

	private JdbcTemplate jdbc;
	private ScopeSeam seam;
	private LeaseManager leaseManager;
	private FencedWrite fencedWrite;
	private EventBufferRepository buffer;

	@BeforeAll
	static void migrate() {
		// Edge's own schema first: migratedSchema is what creates the integration
		// database, and nothing administrative can connect to it before that.
		dataSource = PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");

		// Then core's published view, standing in for orca-core having migrated.
		// Edge reads its lane list through it, so LaneOwnership cannot be exercised
		// without one — and without the grant, edge cannot see it at all, which is
		// ADR-009 working rather than a test detail.
		publishTopologyLane();
		grantTopologyLaneTo("it_" + SCHEMA);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		seam = new JdbcScopeSeam(jdbc);
		leaseManager = new JdbcLeaseManager(jdbc, "orca-edge");
		fencedWrite = new FencedWrite(jdbc, new TransactionTemplate(
				new DataSourceTransactionManager(dataSource)), "orca-edge");
		buffer = new EventBufferRepository(seam);
		jdbc.execute("DELETE FROM event_buffer");
		jdbc.execute("DELETE FROM service_lease");
	}

	// ------------------------------------------------------------------------
	// Property 1 — sever the link under sustained ingest. Zero loss, order kept.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 15, unit = TimeUnit.MINUTES)
	@DisplayName("severing the link under sustained ingest loses nothing, and the drain is still in order")
	void severingTheLinkLosesNothingAndPreservesOrder() {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);
		assertThat(ownership.owns(LANE)).as("this instance must own the lane before it ingests").isTrue();

		AtomicBoolean linkUp = new AtomicBoolean(false);
		List<String> delivered = new CopyOnWriteArrayList<>();
		DeliveryPump pump = new DeliveryPump(buffer, ownership, (lane, batch) -> {
			if (!linkUp.get()) {
				throw new IllegalStateException("runtime is unreachable");
			}
			batch.forEach(event -> delivered.add(event.eventUuid()));
		}, 25, 100);

		// --- the link is down, and trucks keep arriving -----------------------
		List<String> ingested = new ArrayList<>();
		inScope(() -> {
			for (int i = 0; i < 200; i++) {
				String uuid = "evt-%04d".formatted(i);
				ingested.add(uuid);
				buffer.append(capture(uuid));
				if (i % 10 == 0) {
					// The pump keeps trying throughout, which is the point: every one
					// of these attempts marks rows DISPATCHED and then has to put them
					// back. A buffer that lost events would lose them here.
					pump.drainOnce();
				}
			}
		});

		assertThat(delivered).as("the link is severed — nothing can have arrived").isEmpty();
		assertThat(bufferCount()).as("and nothing has been dropped either").isEqualTo(200);

		// --- the link returns -------------------------------------------------
		linkUp.set(true);
		inScope(() -> {
			for (int pass = 0; pass < 40 && delivered.size() < ingested.size(); pass++) {
				pump.drainOnce();
			}
		});

		assertThat(delivered)
				.as("zero loss AND preserved order — §B10 asks for both, and a buffer that "
						+ "redelivered out of order would pass a count-only assertion")
				.containsExactlyElementsOf(ingested);

		inScope(() -> assertThat(buffer.countByStatus(SITE, BufferedEvent.ACKED)).isEqualTo(200));
		log.info("WP5 · severed-link drain: {} events ingested while down, {} delivered in order on return",
				ingested.size(), delivered.size());
	}

	@Test
	@DisplayName("a redelivered batch is not re-buffered — the camera's dedup key does its job")
	void aRepeatedCaptureIsNotBufferedTwice() {
		inScope(() -> {
			assertThat(buffer.append(capture("evt-dup"))).isEqualTo(1);
			// A camera that never saw its acknowledgement sends the same capture
			// again. It must not become two visits' worth of events.
			assertThat(buffer.append(capture("evt-dup"))).isZero();
		});
		assertThat(bufferCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("an event that cannot be delivered becomes DEAD and stays visible — never discarded")
	void anUndeliverableEventIsRetiredRatherThanDropped() {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		DeliveryPump pump = new DeliveryPump(buffer, ownership, (lane, batch) -> {
			throw new IllegalStateException("runtime rejects this batch, permanently");
		}, 10, 3);

		inScope(() -> {
			buffer.append(capture("evt-doomed"));
			for (int attempt = 0; attempt < 5; attempt++) {
				pump.drainOnce();
			}
			assertThat(buffer.countByStatus(SITE, BufferedEvent.DEAD))
					.as("a delivery failure nobody can see is indistinguishable from a capture that "
							+ "never happened")
					.isEqualTo(1);
		});
		assertThat(bufferCount()).as("DEAD, not deleted").isEqualTo(1);
	}

	// ------------------------------------------------------------------------
	// Property 2 — one owner per lane, and the zombie's write is refused.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("two instances, one lane: exactly one owns it, the other takes over on expiry")
	void exactlyOneInstanceOwnsALaneAndHandoverWorks() {
		LaneOwnership a = ownershipFor("instance-a");
		LaneOwnership b = ownershipFor("instance-b");

		inScope(a::reconcile);
		inScope(b::reconcile);

		assertThat(a.owns(LANE)).isTrue();
		assertThat(b.owns(LANE))
				.as("a camera addresses ONE endpoint; two instances serving one lane is two "
						+ "acknowledgements for one capture")
				.isFalse();

		Lease staleLease = a.leaseFor(LANE).orElseThrow();

		// Instance A stalls. Its lease expires by the DATABASE's clock — never by
		// anybody's local one (§B8) — so this is expressed as the database sees it.
		expireLease(LANE);

		inScope(b::reconcile);
		assertThat(b.owns(LANE)).as("the successor takes the lane once the lease has expired").isTrue();
		assertThat(b.leaseFor(LANE).orElseThrow().fenceToken())
				.as("and takes it with a HIGHER fence token, which is what makes the refusal below "
						+ "decidable by the database rather than by trust")
				.isGreaterThan(staleLease.fenceToken());

		// --- the zombie wakes up ---------------------------------------------
		// Instance A never learned it lost the lane. It finishes the capture it was
		// in the middle of, presenting the only lease it has.
		assertThatThrownBy(() -> inScope(() ->
				fencedWrite.execute(staleLease, () -> buffer.append(capture("evt-zombie")))))
				.as("you cannot guarantee a stalled process is dead. You can guarantee its writes "
						+ "are refused")
				.isInstanceOf(StaleFenceTokenException.class);

		assertThat(bufferCount())
				.as("and the refusal rolled back — no half-written capture in the buffer the "
						+ "successor is already draining")
				.isZero();

		// The rightful owner still writes.
		inScope(() -> fencedWrite.execute(b.leaseFor(LANE).orElseThrow(),
				() -> buffer.append(capture("evt-successor"))));
		assertThat(bufferCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("the lease name is the one §C3 names, so an operator reading the table recognises it")
	void theLeaseNameIsTheDocumentedOne() {
		LaneOwnership a = ownershipFor("instance-a");
		inScope(a::reconcile);

		String name = jdbc.queryForObject(
				"SELECT lease_name FROM service_lease WHERE holder_id = 'instance-a'", String.class);

		assertThat(name)
				.as("§C3 writes it out: edge.ingest:lane:<lane_id>. Per-lane scope rides in the "
						+ "lease name, which is how one mechanism covers a retention job, a feed "
						+ "reader and this alike")
				.isEqualTo(LaneOwnership.LEASE_PREFIX + LANE);
	}

	@Test
	@DisplayName("background work with no scope established writes nothing — there is no implicit entitlement")
	void backgroundWorkWithoutScopeCannotBuffer() {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);
		Lease lease = ownership.leaseFor(LANE).orElseThrow();

		// Holding the lease is ownership, not entitlement. No ScopeContext here.
		assertThatThrownBy(() -> fencedWrite.execute(lease, () -> buffer.append(capture("evt-unscoped"))))
				.isInstanceOf(com.lynxis.orca.platform.scope.ScopeViolationException.class);

		assertThat(bufferCount()).isZero();
	}

	// ------------------------------------------------------------------------
	// Property 3 — the listener stores before it acknowledges.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("the camera is acknowledged only after the capture is durably stored")
	void theAcknowledgementFollowsTheRow() throws Exception {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		try (com.lynxis.orca.edge.domain.LprListener listener = new com.lynxis.orca.edge.domain.LprListener(
				0, SITE, new com.lynxis.orca.edge.domain.LprFraming.LengthPrefixedXml(),
				buffer, ownership, fencedWrite)) {
			listener.start();

			String uuid = "evt-" + UUID.randomUUID();
			String ack = sendCapture(listener.boundPort(), LANE, uuid);

			// The acknowledgement is a DURABILITY RECEIPT. A camera that has been
			// told "received" does not send that capture again, so if the row can be
			// absent at this point, a restart in the gap is permanent data loss with
			// nothing to show for it.
			assertThat(ack).contains("STORED").contains(uuid);
			assertThat(bufferCount())
					.as("the row exists BY THE TIME the acknowledgement arrives — that ordering is "
							+ "the whole guarantee")
					.isEqualTo(1);
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a lane this instance does not own is neither stored nor acknowledged")
	void aLaneThisInstanceDoesNotOwnIsNotAcknowledged() throws Exception {
		LaneOwnership a = ownershipFor("instance-a");
		LaneOwnership b = ownershipFor("instance-b");
		inScope(a::reconcile);
		inScope(b::reconcile);
		assertThat(b.owns(LANE)).isFalse();

		// B is running and listening, but does not own the lane. If it answered, one
		// capture would be acknowledged by two instances — and the camera would
		// consider it delivered twice over.
		try (com.lynxis.orca.edge.domain.LprListener listener = new com.lynxis.orca.edge.domain.LprListener(
				0, SITE, new com.lynxis.orca.edge.domain.LprFraming.LengthPrefixedXml(),
				buffer, b, fencedWrite)) {
			listener.start();

			String ack = sendCapture(listener.boundPort(), LANE, "evt-" + UUID.randomUUID());

			assertThat(ack).as("silence, not an acknowledgement").isNull();
			assertThat(bufferCount()).isZero();
		}
	}

	/** Speaks the ⚠️ PROVISIONAL dialect — length-prefixed UTF-8 XML. See LprFraming. */
	private static String sendCapture(int port, String lane, String eventUuid) throws Exception {
		String frame = "<capture><eventUuid>" + eventUuid + "</eventUuid>"
				+ "<laneExternalId>" + lane + "</laneExternalId>"
				+ "<deviceExternalId>DEV-IT-CAMERA</deviceExternalId>"
				+ "<plate>T-1234</plate><imagePath>/var/lpr/1.jpg</imagePath></capture>";
		byte[] body = frame.getBytes(java.nio.charset.StandardCharsets.UTF_8);

		try (java.net.Socket camera = new java.net.Socket("localhost", port)) {
			camera.setSoTimeout(10_000);
			java.io.DataOutputStream out = new java.io.DataOutputStream(camera.getOutputStream());
			out.writeInt(body.length);
			out.write(body);
			out.flush();

			java.io.DataInputStream in = new java.io.DataInputStream(camera.getInputStream());
			try {
				int length = in.readInt();
				byte[] ack = new byte[length];
				in.readFully(ack);
				return new String(ack, java.nio.charset.StandardCharsets.UTF_8);
			}
			catch (java.io.EOFException | java.net.SocketTimeoutException noAnswer) {
				return null;
			}
		}
	}

	// ------------------------------------------------------------------------

	private LaneOwnership ownershipFor(String holderId) {
		return new LaneOwnership(leaseManager, seam, holderId, Duration.ofSeconds(30), SITE);
	}

	private void inScope(Runnable work) {
		ScopeContext.runIn(Scope.of("site_external_id", Set.of(SITE)), work);
	}

	private static BufferedEvent capture(String eventUuid) {
		return new BufferedEvent(0, eventUuid, SITE, LANE, "DEV-IT-CAMERA", "lpr.capture",
				"<capture><eventUuid>" + eventUuid + "</eventUuid></capture>",
				BufferedEvent.PENDING, 0, null, null, null, null);
	}

	private long bufferCount() {
		Long count = jdbc.queryForObject("SELECT COUNT(*) FROM event_buffer", Long.class);
		return count == null ? 0 : count;
	}

	/** Expiry is the database's judgement, so it is expressed in the database's terms. */
	private void expireLease(String lane) {
		jdbc.update("UPDATE service_lease SET expires_at = DATEADD(second, -1, SYSUTCDATETIME()) "
				+ "WHERE lease_name = ?", LaneOwnership.LEASE_PREFIX + lane);
	}

	private static void publishTopologyLane() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT ''" + SITE + "'' AS site_external_id, "
				+ "''" + LANE + "'' AS lane_external_id')");
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

	@SuppressWarnings("unused")
	private static String randomUuid() {
		return UUID.randomUUID().toString();
	}
}
