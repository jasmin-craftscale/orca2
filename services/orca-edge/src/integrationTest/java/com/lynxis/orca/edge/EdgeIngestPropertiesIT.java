package com.lynxis.orca.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import com.lynxis.orca.edge.domain.LprFraming;
import com.lynxis.orca.edge.domain.LprListener;
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
 * <strong>The two ingestion properties edge exists to hold.</strong>
 *
 * <p><em>Sever the link under load, assert zero
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
		// the published-view integration working rather than a test detail.
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
		// anybody's local one, so this is expressed as the database sees it.
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
	// Property 3 — the listener stores before it acknowledges, and speaks the
	// framing translated from the fielded 1.x listener.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("the camera is acknowledged only after the capture is durably stored")
	void theAcknowledgementFollowsTheRow() throws Exception {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		try (LprListener listener = listenerOwnedBy(ownership)) {
			listener.start();

			String uuid = "evt-" + UUID.randomUUID();
			List<String> answers = exchange(listener.boundPort(),
					framed(zapPacket(LANE, uuid, "T-1234", "0.91")));

			// The acknowledgement is a DURABILITY RECEIPT. A camera that has been
			// told "received" does not send that capture again, so if the row can be
			// absent at this point, a restart in the gap is permanent data loss with
			// nothing to show for it.
			assertThat(answers).singleElement().satisfies(ack -> assertThat(ack)
					.as("the exact bytes 1.x sends, Id echoed from the inbound packet")
					.isEqualTo("<ZapPacket Type=\"ACK\" Id=\"pkt-" + uuid
							+ "\" Version=\"4.4\" SenderId=\"999\"></ZapPacket>"));
			assertThat(bufferCount())
					.as("the row exists BY THE TIME the acknowledgement arrives — that ordering is "
							+ "the whole guarantee")
					.isEqualTo(1);

			// Edge is the hardware boundary: the vendor's dialect is decoded
			// once, here, and what crosses to runtime carries no ZapPacket in it.
			assertThat(attributesOf(uuid))
					.as("the winning plate, by highest Confidence, 1-based index — §4 of the "
							+ "wire-format document")
					.contains("\"plate\":\"T-1234\"")
					.contains("\"resultIndex\":1");
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("the highest-confidence plate wins, not the first one the camera listed")
	void theWinningPlateIsTheMostConfidentOne() throws Exception {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		try (LprListener listener = listenerOwnedBy(ownership)) {
			listener.start();

			String uuid = "evt-" + UUID.randomUUID();
			// Three hypotheses, the best one third. A reader that took the first would
			// pass every single-plate test and put the wrong truck through the gate.
			String packet = "<ZapPacket Type=\"MSG\" Id=\"pkt-" + uuid + "\" Version=\"4.4\" "
					+ "SenderId=\"DEV-IT-CAMERA\"><Event><EventGuid>" + uuid + "</EventGuid>"
					+ "<LaneId>" + LANE + "</LaneId>"
					+ "<LP><AutoLPR>WRONG-1</AutoLPR><Confidence>0.42</Confidence></LP>"
					+ "<LP><AutoLPR>WRONG-2</AutoLPR><Confidence>not-a-number</Confidence></LP>"
					+ "<LP><AutoLPR>RIGHT</AutoLPR><Confidence>0.97</Confidence>"
					+ "<CharConfidence>0.95</CharConfidence></LP>"
					+ "</Event></ZapPacket>";

			assertThat(exchange(listener.boundPort(), framed(packet))).singleElement()
					.asString().contains("Type=\"ACK\"");

			assertThat(attributesOf(uuid))
					.contains("\"plate\":\"RIGHT\"")
					.as("1-based, because that is the numbering 1.x records and an operator "
							+ "comparing the two systems will see")
					.contains("\"resultIndex\":3");
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("two packets in one TCP write, and one packet split across two — both are normal")
	void theFramingSurvivesCoalescedAndSplitPackets() throws Exception {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		try (LprListener listener = listenerOwnedBy(ownership)) {
			listener.start();

			// There is no length prefix, so a reader that assumed one packet per read
			// would lose the second — and one that assumed a whole packet per read
			// would corrupt the third.
			String first = "evt-" + UUID.randomUUID();
			String second = "evt-" + UUID.randomUUID();
			String third = "evt-" + UUID.randomUUID();

			byte[] coalesced = concat(framed(zapPacket(LANE, first, "AAA-111", "0.9")),
					framed(zapPacket(LANE, second, "BBB-222", "0.9")));
			byte[] whole = framed(zapPacket(LANE, third, "CCC-333", "0.9"));

			try (java.net.Socket camera = new java.net.Socket("localhost", listener.boundPort())) {
				camera.setSoTimeout(10_000);
				OutputStream out = camera.getOutputStream();
				InputStream in = camera.getInputStream();

				out.write(coalesced);
				out.flush();
				assertThat(readFrame(in)).as("first of two in one write").contains("Id=\"pkt-" + first);
				assertThat(readFrame(in)).as("second of two in one write").contains("Id=\"pkt-" + second);

				// Split mid-XML, then flushed — a real TCP stream does this whenever
				// the packet is larger than a segment.
				int cut = whole.length / 2;
				out.write(whole, 0, cut);
				out.flush();
				Thread.sleep(200);
				out.write(whole, cut, whole.length - cut);
				out.flush();
				assertThat(readFrame(in)).as("one packet across two writes").contains("Id=\"pkt-" + third);
			}

			assertThat(bufferCount()).isEqualTo(3);
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a packet that does not decode is NAKed and the CONNECTION SURVIVES — 1.x kills it")
	void aMalformedPacketFailsThePacketAndNotTheConnection() throws Exception {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		try (LprListener listener = listenerOwnedBy(ownership)) {
			listener.start();

			String good = "evt-" + UUID.randomUUID();
			try (java.net.Socket camera = new java.net.Socket("localhost", listener.boundPort())) {
				camera.setSoTimeout(10_000);
				OutputStream out = camera.getOutputStream();
				InputStream in = camera.getInputStream();

				out.write(framed("<ZapPacket Type=\"MSG\" Id=\"pkt-broken\"><Event><EventGuid>"));
				out.flush();

				// Legacy defect: 1.x `return`s out of its read loop here, so a per-packet
				// fault becomes a connection fault and every LATER capture on that
				// connection is lost with it.
				assertThat(readFrame(in))
						.as("the Id is empty because it lives in the packet that failed to parse — "
								+ "1.x's own quirk, reproduced rather than papered over")
						.isEqualTo("<ZapPacket Type=\"NAK\" Id=\"\" Version=\"4.4\" "
								+ "SenderId=\"999\"></ZapPacket>");

				out.write(framed(zapPacket(LANE, good, "T-9999", "0.9")));
				out.flush();
				assertThat(readFrame(in))
						.as("THE POINT OF THIS TEST: the next capture on the same connection is "
								+ "still served")
						.contains("Type=\"ACK\"");
			}

			assertThat(bufferCount()).isEqualTo(1);
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a capture that could not be stored is NAKed, never ACKed — 1.x acks regardless")
	void aCaptureThatCouldNotBeStoredIsNotAcknowledged() throws Exception {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		try (LprListener listener = listenerOwnedBy(ownership)) {
			listener.start();

			// The instance still believes it owns the lane; the database says the lease
			// has expired. FencedWrite refuses the write and rolls it back.
			expireLease(LANE);

			List<String> answers = exchange(listener.boundPort(),
					framed(zapPacket(LANE, "evt-" + UUID.randomUUID(), "T-0000", "0.9")));

			// Legacy defect: 1.x writes its ACK after the publish ATTEMPT, so a failed
			// publish and a successful one are indistinguishable to the camera — and
			// the camera never sends that capture again.
			assertThat(answers).singleElement().asString()
					.as("the acknowledgement is a durability receipt. There is no durable row")
					.contains("Type=\"NAK\"");
			assertThat(bufferCount()).isZero();
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a capture with no EventGuid is refused — there is no dedup key to store it under")
	void aCaptureWithNoDedupKeyIsRefused() throws Exception {
		LaneOwnership ownership = ownershipFor("instance-a");
		inScope(ownership::reconcile);

		try (LprListener listener = listenerOwnedBy(ownership)) {
			listener.start();

			String packet = "<ZapPacket Type=\"MSG\" Id=\"pkt-nokey\" Version=\"4.4\" SenderId=\"DEV\">"
					+ "<Event><LaneId>" + LANE + "</LaneId>"
					+ "<LP><AutoLPR>T-1234</AutoLPR><Confidence>0.9</Confidence></LP>"
					+ "</Event></ZapPacket>";

			// A DECLARED DEVIATION from 1.x, not an oversight: 1.x would have stored
			// nothing and acknowledged anyway. Synthesising a key here would turn one
			// camera retry into two captures, which is the defect the dedup key exists
			// to prevent.
			assertThat(exchange(listener.boundPort(), framed(packet))).singleElement()
					.asString().contains("Type=\"NAK\"").contains("Id=\"pkt-nokey\"");
			assertThat(bufferCount()).isZero();
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a lane this instance does not own is neither stored nor answered at all")
	void aLaneThisInstanceDoesNotOwnIsNotAcknowledged() throws Exception {
		LaneOwnership a = ownershipFor("instance-a");
		LaneOwnership b = ownershipFor("instance-b");
		inScope(a::reconcile);
		inScope(b::reconcile);
		assertThat(b.owns(LANE)).isFalse();

		// B is running and listening, but does not own the lane. If it answered — with
		// an ACK or a NAK — it would be answering for a peer that is about to accept.
		try (LprListener listener = listenerOwnedBy(b)) {
			listener.start();

			assertThat(exchange(listener.boundPort(),
					framed(zapPacket(LANE, "evt-" + UUID.randomUUID(), "T-1234", "0.9"))))
					.as("silence, not an acknowledgement and not a refusal")
					.isEmpty();
			assertThat(bufferCount()).isZero();
		}
	}

	// ------------------------------------------------------------------------
	// The camera's side of the wire, translated from the legacy 1.x listener.
	// See docs/lpr-wire-format-from-1x.md.
	// ------------------------------------------------------------------------

	private static final byte STX = 0x02;
	private static final byte ETX = 0x03;

	private LprListener listenerOwnedBy(LaneOwnership ownership) {
		return new LprListener(0, SITE, new LprFraming.ZapPacketStxEtx(), buffer, ownership,
				fencedWrite, tools.jackson.databind.json.JsonMapper.builder().build());
	}

	private static String zapPacket(String lane, String eventGuid, String plate, String confidence) {
		return "<ZapPacket Type=\"MSG\" Id=\"pkt-" + eventGuid + "\" Version=\"4.4\" "
				+ "SenderId=\"DEV-IT-CAMERA\" SenderName=\"IT camera\">"
				+ "<Event>"
				+ "<EventId>1</EventId><EventGuid>" + eventGuid + "</EventGuid>"
				+ "<Online>true</Online><TimeStamp>2026-08-07T09:00:00</TimeStamp>"
				+ "<LaneId>" + lane + "</LaneId><LaneName>" + lane + "</LaneName>"
				+ "<LP><AutoLPR>" + plate + "</AutoLPR><Confidence>" + confidence + "</Confidence>"
				+ "<CharConfidence>0.88</CharConfidence>"
				// Images travel as PATHS, never as bytes.
				+ "<LPRImage TIN=\"1\" CameraId=\"CAM-1\"><Path>/var/lpr/1.jpg</Path></LPRImage>"
				+ "</LP>"
				+ "</Event></ZapPacket>";
	}

	private static byte[] framed(String packet) {
		byte[] body = packet.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] framed = new byte[body.length + 2];
		framed[0] = STX;
		System.arraycopy(body, 0, framed, 1, body.length);
		framed[framed.length - 1] = ETX;
		return framed;
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] both = new byte[first.length + second.length];
		System.arraycopy(first, 0, both, 0, first.length);
		System.arraycopy(second, 0, both, first.length, second.length);
		return both;
	}

	/** Sends the bytes, then collects whatever the listener answers before it goes quiet. */
	private static List<String> exchange(int port, byte[] bytes) throws Exception {
		try (java.net.Socket camera = new java.net.Socket("localhost", port)) {
			// Short, because "no answer" is an EXPECTED outcome in two of the tests
			// above and a long timeout would make each of them cost a minute.
			camera.setSoTimeout(3_000);
			camera.getOutputStream().write(bytes);
			camera.getOutputStream().flush();

			List<String> answers = new ArrayList<>();
			InputStream in = camera.getInputStream();
			for (String answer = readFrame(in); answer != null; answer = readFrame(in)) {
				answers.add(answer);
			}
			return answers;
		}
	}

	/** Reads one STX/ETX-delimited frame, or null when none arrives. */
	private static String readFrame(InputStream in) throws IOException {
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		try {
			int b;
			while ((b = in.read()) != STX) {
				if (b < 0) {
					return null;
				}
			}
			while ((b = in.read()) != ETX) {
				if (b < 0) {
					return null;
				}
				body.write(b);
			}
		}
		catch (java.net.SocketTimeoutException noAnswer) {
			return null;
		}
		return body.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private String attributesOf(String eventUuid) {
		return jdbc.queryForObject("SELECT attributes FROM event_buffer WHERE event_uuid = ?",
				String.class, eventUuid);
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
				zapPacket(LANE, eventUuid, "T-1234", "0.91"), "{\"plate\":\"T-1234\"}",
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

	/**
	 * ⚠️ Deliberately the SAME view definition {@code DeviceCommandPropertiesIT}
	 * creates. The two suites share one integration database and one {@code core}
	 * schema, so the one that ran last is the one whose definition survives — and
	 * two definitions would make the pair pass or fail by test ordering, which is
	 * the worst kind of green.
	 */
	private static void publishTopologyLane() {
		DeviceCommandPropertiesIT.EdgeTopologyFixture.publishTopologyLane(SITE, LANE, "http://localhost:1");
	}

	private static void grantTopologyLaneTo(String login) {
		DeviceCommandPropertiesIT.EdgeTopologyFixture.grantTo(login);
	}

	@SuppressWarnings("unused")
	private static String randomUuid() {
		return UUID.randomUUID().toString();
	}
}
