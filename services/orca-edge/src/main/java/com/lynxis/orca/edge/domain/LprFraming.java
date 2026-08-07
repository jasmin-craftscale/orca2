package com.lynxis.orca.edge.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * How a plate camera's messages are framed on the wire.
 *
 * <p><strong>DERIVED-FROM-1X.</strong> Every byte in this file comes from
 * {@code docs/lpr-wire-format-from-1x.md}, which was extracted from the ORCA 1.x
 * production listener — the Go service that talks to real cameras today. It is
 * <em>not</em> a vendor specification and it is not a capture from a fielded unit;
 * both remain worth obtaining, and §6 of that document lists what neither the 1.x
 * code nor this file can answer (retry-on-NAK behaviour, fields beyond the 1.x
 * DTOs, charset corner cases).
 *
 * <p>It supersedes the length-prefixed guess this class shipped with in WP5, which
 * {@code phase-1-report.md} §5.6 recorded as a provisional reading of §D2's one
 * sentence. <strong>That reading was wrong against the fielded estate.</strong> The
 * framing is delimiter-based:
 *
 * <pre>
 * STX = 0x02 · ETX = 0x03
 * on the wire:   [0x02] &lt;ZapPacket ...&gt;…&lt;/ZapPacket&gt; [0x03]
 * </pre>
 *
 * <p>There is no length prefix anywhere. The reader accumulates the TCP stream,
 * scans for {@code STX} and then for the first {@code ETX} after it; the payload is
 * the bytes <em>strictly between</em> the two. <strong>Multiple packets in one read
 * and one packet split across reads are both normal</strong> — which is why reading
 * is a per-connection object with a buffer rather than a static call on a stream.
 *
 * <h2>Three things 1.x does that this deliberately does not</h2>
 *
 * <p>Listed in §5 of the source document as behaviours observed in the fielded
 * handler, so that nobody mistakes them for contract:
 *
 * <ol>
 *   <li><strong>1.x acknowledges regardless of outcome.</strong> Its ACK is written
 *       after the publish <em>attempt</em>, even when the publish failed, and events
 *       with an empty plate are skipped and acknowledged anyway. Here the ACK
 *       follows the durable row and nothing else — see {@link LprListener}.</li>
 *   <li><strong>A mid-stream decode failure kills the whole 1.x connection
 *       handler.</strong> A per-packet fault becomes a connection fault, and every
 *       later capture on that connection is lost with it. Here a packet fails and
 *       the connection carries on.</li>
 *   <li><strong>1.x has no dedup at this layer.</strong> {@code Event/EventGuid}
 *       becomes the buffer's {@code event_uuid}, so a camera retrying after a lost
 *       acknowledgement produces one row rather than two.</li>
 * </ol>
 */
public interface LprFraming {

	/**
	 * Opens a reader over one camera connection.
	 *
	 * <p>Per connection, because the framing state — a partial packet that arrived
	 * split across two TCP reads — belongs to the connection and to nothing else.
	 */
	PacketReader reader(InputStream in);

	/**
	 * Writes the acknowledgement the camera waits for.
	 *
	 * <p>Called <strong>only after the capture is durably stored</strong>. The
	 * acknowledgement is a durability receipt, not a courtesy: a camera that has
	 * been told "received" will not send that capture again.
	 *
	 * @param packetId the inbound packet's {@code Id}, echoed back
	 */
	void writeAck(OutputStream out, String packetId) throws IOException;

	/**
	 * Writes the negative acknowledgement.
	 *
	 * <p>1.x sends this on a parse failure alone. Here it also covers a packet this
	 * instance definitively refuses — see {@link LprListener}, which states which
	 * cases and why silence is the answer for the remaining one.
	 *
	 * @param packetId the inbound packet's {@code Id}, or empty when the packet
	 *                 failed to parse and there is no id to echo. That emptiness is
	 *                 1.x's own quirk, reproduced rather than papered over: the id
	 *                 comes from the packet that could not be read
	 */
	void writeNak(OutputStream out, String packetId) throws IOException;

	/** One camera connection's stream of complete packets. */
	interface PacketReader {

		/**
		 * The next complete packet's payload — the bytes between {@code STX} and
		 * {@code ETX}, exclusive — or {@code null} at end of stream.
		 *
		 * <p>A partial packet left in the buffer when the camera disconnects is
		 * discarded rather than returned: half a packet is not a capture, and
		 * guessing at the rest is how a plate becomes the wrong plate.
		 *
		 * @throws IOException when the stream has lost its framing — a run of bytes
		 *                     longer than any real packet with no {@code ETX} in it.
		 *                     The connection is then closed, because a stream whose
		 *                     framing is lost cannot be trusted to resume at a
		 *                     packet boundary
		 */
		String next() throws IOException;
	}

	/**
	 * DERIVED-FROM-1X · the STX/ETX-delimited framing the fielded estate speaks.
	 *
	 * <p>Sources, for whoever checks this against the Go: the framing bytes are
	 * {@code internal/utils/constants.go:76-77}; the accumulate-and-scan reader is
	 * {@code internal/handlers/data_capture_device_handler.go} ~424–659; the two
	 * responses below are {@code internal/utils/utils.go:280-288}.
	 */
	final class ZapPacketStxEtx implements LprFraming {

		/** Start of text. The packet begins after this byte. */
		public static final byte STX = 0x02;

		/** End of text. The packet ends before this byte. */
		public static final byte ETX = 0x03;

		/**
		 * The protocol version 1.x speaks and every fielded unit answers to.
		 *
		 * <p>⚠️ Not negotiated: 1.x writes {@code "4.4"} into every response
		 * unconditionally, so this is what the estate expects rather than what the
		 * vendor supports.
		 */
		public static final String VERSION = "4.4";

		/**
		 * ⚠️ 1.x's own sender id, echoed verbatim.
		 *
		 * <p>It is a magic number in the Go and it is a magic number here. Changing
		 * it is a change to what fielded cameras receive, so it is not ours to pick:
		 * a camera that filters on it would stop accepting our acknowledgements.
		 */
		public static final String SENDER_ID = "999";

		/**
		 * A run of bytes longer than this with no {@code ETX} is a lost stream, not
		 * a large packet.
		 *
		 * <p>Images travel as filesystem paths and never as bytes (§D2, and §2 of the
		 * source document), so a real packet is a few kilobytes of XML.
		 */
		private static final int MAX_PACKET_BYTES = 1 << 20;

		@Override
		public PacketReader reader(InputStream in) {
			return new StxEtxReader(in);
		}

		@Override
		public void writeAck(OutputStream out, String packetId) throws IOException {
			write(out, "ACK", packetId);
		}

		@Override
		public void writeNak(OutputStream out, String packetId) throws IOException {
			write(out, "NAK", packetId);
		}

		private static void write(OutputStream out, String type, String packetId) throws IOException {
			String body = "<ZapPacket Type=\"" + type + "\" Id=\"" + attribute(packetId)
					+ "\" Version=\"" + VERSION + "\" SenderId=\"" + SENDER_ID + "\"></ZapPacket>";
			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			byte[] framed = new byte[payload.length + 2];
			framed[0] = STX;
			System.arraycopy(payload, 0, framed, 1, payload.length);
			framed[framed.length - 1] = ETX;
			out.write(framed);
			out.flush();
		}

		/**
		 * Escapes the echoed id.
		 *
		 * <p>1.x interpolates it unescaped. For an ordinary id the bytes are
		 * identical, so this is not a contract change; for an id carrying a quote it
		 * is the difference between a well-formed response and one the camera's own
		 * parser rejects. Reported rather than silently mirrored.
		 */
		private static String attribute(String value) {
			if (value == null) {
				return "";
			}
			return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
		}

		/**
		 * The accumulate-and-scan reader.
		 *
		 * <p>Bytes before the first {@code STX} are discarded: 1.x scans for the
		 * delimiter rather than assuming the stream starts on one, which is what
		 * makes a reconnect mid-packet recoverable.
		 *
		 * <p>No escaping of {@code 0x02}/{@code 0x03} inside the payload is handled,
		 * because 1.x handles none — the payload is XML text, where raw control bytes
		 * do not legitimately occur.
		 */
		private static final class StxEtxReader implements PacketReader {

			private final InputStream in;
			private final byte[] chunk = new byte[8192];

			private byte[] buffer = new byte[8192];
			private int size;

			private StxEtxReader(InputStream in) {
				this.in = in;
			}

			@Override
			public String next() throws IOException {
				for (;;) {
					String complete = takeComplete();
					if (complete != null) {
						return complete;
					}
					if (size >= MAX_PACKET_BYTES) {
						throw new IOException("No ETX in " + size + " bytes. The stream has lost its "
								+ "framing — see LprFraming: packets are STX/ETX-delimited and a real "
								+ "one is a few kilobytes of XML, because images travel as paths.");
					}
					int read = in.read(chunk);
					if (read < 0) {
						// End of stream. Whatever is left is a partial packet, and half
						// a packet is not a capture.
						return null;
					}
					append(read);
				}
			}

			private String takeComplete() {
				int start = indexOf(STX, 0);
				if (start < 0) {
					// Nothing framed yet. None of these bytes can become a packet.
					size = 0;
					return null;
				}
				int end = indexOf(ETX, start + 1);
				if (end < 0) {
					discardThrough(start - 1);
					return null;
				}
				String payload = new String(buffer, start + 1, end - start - 1, StandardCharsets.UTF_8);
				discardThrough(end);
				return payload;
			}

			private int indexOf(byte wanted, int from) {
				for (int i = from; i < size; i++) {
					if (buffer[i] == wanted) {
						return i;
					}
				}
				return -1;
			}

			/** Drops everything up to and including {@code last}, keeping the rest for the next packet. */
			private void discardThrough(int last) {
				int remaining = size - (last + 1);
				if (remaining > 0) {
					System.arraycopy(buffer, last + 1, buffer, 0, remaining);
				}
				size = Math.max(remaining, 0);
			}

			private void append(int read) {
				if (size + read > buffer.length) {
					byte[] grown = new byte[Math.max(buffer.length * 2, size + read)];
					System.arraycopy(buffer, 0, grown, 0, size);
					buffer = grown;
				}
				System.arraycopy(chunk, 0, buffer, size, read);
				size += read;
			}
		}
	}
}
