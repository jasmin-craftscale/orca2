package com.lynxis.orca.edge.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;
import com.lynxis.orca.edge.persistence.EventBufferRepository;
import com.lynxis.orca.platform.lease.FencedWrite;
import com.lynxis.orca.platform.lease.Lease;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.system.SystemContext;
import com.lynxis.orca.platform.web.system.SystemIdentity;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The TCP server a plate camera connects to.
 *
 * <p><strong>The one rule that matters here:</strong> the capture is persisted
 * <em>before</em> the acknowledgement is sent. The buffer is durable and a device
 * event must survive a link outage; both are false if the camera
 * is told "received" first, because a camera that has been acknowledged does not
 * send that capture again, and a restart in the gap is then permanent data loss
 * with nothing to show for it.
 *
 * <p>That is also the first of the three 1.x behaviours this deliberately does not
 * copy (see {@code docs/lpr-wire-format-from-1x.md}): 1.x writes its ACK after
 * the publish <em>attempt</em>, so a failed publish and a successful one look
 * identical to the camera.
 *
 * <p><strong>Transport: the JDK, on virtual threads.</strong> Netty and Spring
 * Integration were considered but add no value here: the governing rule is
 * that every dependency addition is recorded with a why — there is no why. A site
 * has a handful of cameras; the framing is a delimiter scan; and Java 25's virtual
 * threads make thread-per-connection the simple shape again rather than the
 * expensive one. Netty would be a large dependency and an event-loop programming
 * model bought for a protocol that needs neither.
 *
 * <h2>What the camera is told, and when</h2>
 *
 * <table>
 *   <tr><th>Case</th><th>Answer</th></tr>
 *   <tr><td>Stored durably</td><td>{@code ACK} — and only then</td></tr>
 *   <tr><td>Not a {@code MSG} packet</td><td>Nothing. 1.x acknowledges captures only</td></tr>
 *   <tr><td>Did not parse</td><td>{@code NAK} with an empty {@code Id} — 1.x's own quirk</td></tr>
 *   <tr><td>No {@code EventGuid} or no {@code LaneId}</td><td>{@code NAK}</td></tr>
 *   <tr><td>The write failed</td><td>{@code NAK}</td></tr>
 *   <tr><td>Another instance owns the lane</td><td>Nothing</td></tr>
 * </table>
 *
 * <p>The last two rows are the ones worth arguing with, so both are written out.
 *
 * <p><strong>Why a refusal is a {@code NAK} and not silence.</strong> 1.x sends
 * {@code NAK} on a parse failure alone. The source document records that
 * nobody here knows how a camera reacts to one. Given that, the choice is between
 * telling the camera something true — <em>this was not taken</em> — and telling it
 * nothing and letting it wait out a timeout. A packet with no {@code EventGuid}
 * cannot be deduplicated and therefore cannot be stored safely; synthesising a key
 * would turn one camera retry into two captures. 1.x would have acknowledged it.
 *
 * <p><strong>Why an unowned lane is silence.</strong> A camera addresses one
 * endpoint, and another instance holds that lane and is expected to answer. A
 * {@code NAK} from the instance that is <em>not</em> serving the lane would be a
 * refusal on behalf of a peer that is about to accept.
 */
@Slf4j
public class LprListener implements AutoCloseable {

	private final int port;
	private final String siteExternalId;
	private final LprFraming framing;
	private final EventBufferRepository buffer;
	private final LaneOwnership ownership;
	private final FencedWrite fencedWrite;
	private final JsonMapper json;

	private final AtomicBoolean running = new AtomicBoolean();
	private ServerSocket serverSocket;
	private ExecutorService connections;

	public LprListener(int port, String siteExternalId, LprFraming framing, EventBufferRepository buffer,
			LaneOwnership ownership, FencedWrite fencedWrite, JsonMapper json) {
		this.port = port;
		this.siteExternalId = siteExternalId;
		this.framing = framing;
		this.buffer = buffer;
		this.ownership = ownership;
		this.fencedWrite = fencedWrite;
		this.json = json;
	}

	public void start() throws IOException {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		serverSocket = new ServerSocket(port);
		connections = Executors.newVirtualThreadPerTaskExecutor();
		Thread.ofVirtual().name("lpr-accept").start(this::acceptLoop);
		log.info("LPR listener accepting on port {} — STX/ETX ZapPacket framing, DERIVED-FROM-1X "
				+ "(see docs/lpr-wire-format-from-1x.md)", serverSocket.getLocalPort());
	}

	/** The bound port. Not always the configured one: 0 means "any free port", which tests use. */
	public int boundPort() {
		return serverSocket == null ? -1 : serverSocket.getLocalPort();
	}

	private void acceptLoop() {
		while (running.get() && !serverSocket.isClosed()) {
			try {
				Socket camera = serverSocket.accept();
				connections.submit(() -> serve(camera));
			}
			catch (IOException closed) {
				if (running.get()) {
					log.warn("LPR accept loop stopped: {}", closed.getMessage());
				}
				return;
			}
		}
	}

	/**
	 * Serves one camera connection until the camera closes it or the framing is lost.
	 *
	 * <p><strong>A packet fault is not a connection fault.</strong> That is the second
	 * 1.x behaviour not copied: its handler {@code return}s out of the read loop on a
	 * decode failure, so one malformed packet takes every later capture on that
	 * connection with it. Here the packet is answered and the loop continues; only a
	 * stream that has genuinely lost its framing ends the connection, because a
	 * reader that cannot find a packet boundary cannot resume at one.
	 */
	private void serve(Socket camera) {
		try (Socket socket = camera;
				InputStream in = socket.getInputStream();
				OutputStream out = socket.getOutputStream()) {
			LprFraming.PacketReader reader = framing.reader(in);
			String payload;
			while ((payload = reader.next()) != null) {
				handle(payload, out);
			}
		}
		catch (IOException lost) {
			log.debug("camera connection ended: {}", lost.getMessage());
		}
	}

	/**
	 * Handles one packet.
	 *
	 * @throws IOException only when the answer cannot be written — the connection is
	 *                     gone, and nothing further can be said on it
	 */
	private void handle(String payload, OutputStream out) throws IOException {
		ZapPacket packet;
		try {
			packet = ZapPacket.parse(payload);
		}
		catch (ZapPacket.MalformedPacketException malformed) {
			// ⚠️ The NAK's Id is empty, and that is 1.x's behaviour reproduced rather
			// than a gap: the id lives in the packet that failed to parse.
			log.warn("a camera packet did not decode ({}). NAK, and the connection continues — "
					+ "1.x would have closed it and lost every later capture", malformed.getMessage());
			framing.writeNak(out, "");
			return;
		}

		if (!packet.isCapture()) {
			// 1.x acknowledges Type="MSG" and nothing else. Answering a non-capture
			// would be inventing protocol on a contract nobody here owns.
			log.debug("ignoring a ZapPacket of type '{}' — only MSG carries a capture", packet.type());
			return;
		}

		if (isBlank(packet.eventGuid()) || isBlank(packet.laneId())) {
			log.warn("a capture arrived with {} — it cannot be buffered, so it is not acknowledged",
					isBlank(packet.eventGuid()) ? "no EventGuid, which is the buffer's dedup key"
							: "no LaneId, so nothing says which lane it belongs to");
			framing.writeNak(out, packet.id());
			return;
		}

		Optional<Lease> lease = ownership.leaseFor(packet.laneId());
		if (lease.isEmpty()) {
			// Another instance owns this lane and is already serving that camera.
			// Answering at all would mean two instances answering one capture.
			log.debug("not this instance's lane: {}", packet.laneId());
			return;
		}

		try {
			store(packet, payload, lease.get());
		}
		catch (RuntimeException notStored) {
			// The lease went stale mid-write, the scope was not established, or the
			// database is unreachable. Whatever the cause, there is no durable row —
			// so there is no acknowledgement, because the acknowledgement IS the
			// durability receipt.
			log.error("a capture for lane {} could not be buffered ({}). NAK: the camera must not "
					+ "believe this one was delivered", packet.laneId(), notStored.toString());
			framing.writeNak(out, packet.id());
			return;
		}

		// Only now. The receipt follows the row, never the other way round.
		framing.writeAck(out, packet.id());
	}

	private void store(ZapPacket packet, String payload, Lease lease) {
		BufferedEvent event = new BufferedEvent(0, packet.eventGuid(), siteExternalId, packet.laneId(),
				packet.senderId(), "lpr.capture", payload, attributesOf(packet),
				BufferedEvent.PENDING, 0, null, null, null, null);

		// The fence token is checked inside the write's own transaction. An instance
		// that stalled long enough to lose the lane has its capture REFUSED here,
		// rather than writing into a buffer another instance is already draining.
		SystemContext.runAs(new SystemIdentity("orca-edge", "lpr-ingest"), () ->
				ScopeContext.runIn(Scope.of("site_external_id", Set.of(siteExternalId)), () ->
						fencedWrite.execute(lease, () -> buffer.append(event))));
	}

	/**
	 * The normalised half of a capture, as JSON.
	 *
	 * <p><strong>Edge is the hardware boundary, so the vendor's dialect stops
	 * here.</strong> The raw packet stays in {@code payload} because that is what
	 * arrived and a durable buffer that paraphrased its input would be worth less
	 * than one that did not; but what crosses to runtime is this — a small, stable
	 * map that has no {@code ZapPacket} in it. Runtime never learns what a
	 * {@code LP} element is, which is the whole point of the boundary.
	 */
	private String attributesOf(ZapPacket packet) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		put(attributes, "plate", packet.plate());
		put(attributes, "confidence", packet.confidence());
		put(attributes, "charConfidence", packet.charConfidence());
		if (packet.resultIndex() > 0) {
			attributes.put("resultIndex", packet.resultIndex());
		}
		put(attributes, "capturedAt", packet.timestamp());
		put(attributes, "packetId", packet.id());
		try {
			return json.writeValueAsString(attributes);
		}
		catch (JacksonException cannotSerialise) {
			// A map of strings that will not serialise is not a condition this can
			// recover from, and dropping the attributes silently would leave the row
			// looking complete.
			throw new IllegalStateException("The normalised capture attributes could not be "
					+ "serialised for lane " + packet.laneId(), cannotSerialise);
		}
	}

	private static void put(Map<String, Object> attributes, String key, String value) {
		if (!isBlank(value)) {
			attributes.put(key, value);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@Override
	public void close() {
		running.set(false);
		try {
			if (serverSocket != null) {
				serverSocket.close();
			}
		}
		catch (IOException ignored) {
			// Shutting down. Nothing useful follows from failing to close a socket.
		}
		if (connections != null) {
			connections.shutdownNow();
		}
	}
}
