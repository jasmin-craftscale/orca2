package com.lynxis.orca.edge.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lynxis.orca.edge.domain.EdgeTables.BufferedEvent;
import com.lynxis.orca.edge.persistence.EventBufferRepository;
import com.lynxis.orca.platform.lease.FencedWrite;
import com.lynxis.orca.platform.lease.Lease;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.system.SystemContext;
import com.lynxis.orca.platform.web.system.SystemIdentity;

import lombok.extern.slf4j.Slf4j;

/**
 * The TCP server a plate camera connects to.
 *
 * <p><strong>The one rule that matters here:</strong> the capture is persisted
 * <em>before</em> the acknowledgement is sent. §C3 calls the buffer durable and
 * §B10 says a device event survives a link outage — both are false if the camera
 * is told "received" first, because a camera that has been acknowledged does not
 * send that capture again, and a restart in the gap is then permanent data loss
 * with nothing to show for it.
 *
 * <p><strong>Transport: the JDK, on virtual threads.</strong> The plan named Netty
 * or Spring Integration. Neither is here, and the reason is the plan's own rule
 * that every dependency addition is recorded with a why — there is no why. A site
 * has a handful of cameras; the framing is length-prefixed bytes; and Java 25's
 * virtual threads make thread-per-connection the simple shape again rather than
 * the expensive one. Netty would be a large dependency and an event-loop
 * programming model bought for a protocol that needs neither. Recorded as a
 * decision the plan did not dictate.
 *
 * <p>⚠️ The wire format it speaks is <strong>provisional</strong> — see
 * {@link LprFraming}. Nothing about the vendor's actual protocol is in this
 * repository, and no capture samples were supplied.
 */
@Slf4j
public class LprListener implements AutoCloseable {

	/** ⚠️ PROVISIONAL, with {@link LprFraming}. Enough to carry a capture; not the vendor's schema. */
	private static final Pattern LANE = Pattern.compile("<laneExternalId>([^<]+)</laneExternalId>");
	private static final Pattern DEVICE = Pattern.compile("<deviceExternalId>([^<]+)</deviceExternalId>");
	private static final Pattern EVENT_UUID = Pattern.compile("<eventUuid>([^<]+)</eventUuid>");

	private final int port;
	private final String siteExternalId;
	private final LprFraming framing;
	private final EventBufferRepository buffer;
	private final LaneOwnership ownership;
	private final FencedWrite fencedWrite;

	private final AtomicBoolean running = new AtomicBoolean();
	private ServerSocket serverSocket;
	private ExecutorService connections;

	public LprListener(int port, String siteExternalId, LprFraming framing, EventBufferRepository buffer,
			LaneOwnership ownership, FencedWrite fencedWrite) {
		this.port = port;
		this.siteExternalId = siteExternalId;
		this.framing = framing;
		this.buffer = buffer;
		this.ownership = ownership;
		this.fencedWrite = fencedWrite;
	}

	public void start() throws IOException {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		serverSocket = new ServerSocket(port);
		connections = Executors.newVirtualThreadPerTaskExecutor();
		Thread.ofVirtual().name("lpr-accept").start(this::acceptLoop);
		log.info("LPR listener accepting on port {} (⚠️ provisional wire format — see LprFraming)",
				serverSocket.getLocalPort());
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

	private void serve(Socket camera) {
		try (Socket socket = camera;
				InputStream in = socket.getInputStream();
				OutputStream out = socket.getOutputStream()) {
			String frame;
			while ((frame = framing.readFrame(in)) != null) {
				String eventUuid = extract(EVENT_UUID, frame);
				if (accept(frame, eventUuid)) {
					// Only now. The receipt follows the row, never the other way round.
					framing.writeAck(out, eventUuid);
				}
			}
		}
		catch (IOException lost) {
			log.debug("camera connection ended: {}", lost.getMessage());
		}
	}

	/**
	 * Stores one capture, if this instance owns its lane.
	 *
	 * @return whether the camera may be acknowledged
	 */
	private boolean accept(String frame, String eventUuid) {
		String lane = extract(LANE, frame);
		if (lane == null || eventUuid == null) {
			log.warn("discarding a frame with no lane or no event uuid — ⚠️ this is the most likely "
					+ "symptom of the provisional wire format not matching the camera's");
			return false;
		}

		Optional<Lease> lease = ownership.leaseFor(lane);
		if (lease.isEmpty()) {
			// Another instance owns this lane and is already serving that camera.
			// Answering anyway would mean two instances acknowledging one capture.
			log.debug("not this instance's lane: {}", lane);
			return false;
		}

		BufferedEvent event = new BufferedEvent(0, eventUuid, siteExternalId, lane,
				extract(DEVICE, frame), "lpr.capture", frame, BufferedEvent.PENDING, 0, null,
				null, null, null);

		// The fence token is checked inside the write's own transaction. An instance
		// that stalled long enough to lose the lane has its capture REFUSED here,
		// rather than writing into a buffer another instance is already draining.
		SystemContext.runAs(new SystemIdentity("orca-edge", "lpr-ingest"), () ->
				ScopeContext.runIn(Scope.of("site_external_id", Set.of(siteExternalId)), () ->
						fencedWrite.execute(lease.get(), () -> buffer.append(event))));
		return true;
	}

	private static String extract(Pattern pattern, String frame) {
		Matcher matcher = pattern.matcher(frame);
		return matcher.find() ? matcher.group(1) : null;
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
