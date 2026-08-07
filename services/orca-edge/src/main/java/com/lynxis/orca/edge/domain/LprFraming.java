package com.lynxis.orca.edge.domain;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * How a plate camera's messages are framed on the wire.
 *
 * <p>⚠️ <strong>THE FRAMING IN THIS FILE IS PROVISIONAL AND IS ALMOST CERTAINLY
 * NOT THE CAMERA'S.</strong> Read this before using it against real hardware.
 *
 * <p>§D2 fixes the LPR contract as <em>"framed XML over TCP, with acknowledgement
 * back to the camera; images referenced by filesystem path"</em>, and calls it
 * frozen because <em>"the protocol belongs to the camera vendor and is burned into
 * units already mounted at gates"</em>. That sentence is the entire specification
 * available in this repository. It does not say what the framing is, what the XML
 * looks like, or what an acknowledgement contains — and no capture samples from
 * the existing estate were provided to this build.
 *
 * <p>So this is an <strong>interface with one provisional implementation</strong>,
 * rather than a parser written as though the format were known. That shape is the
 * honest one: it says in the code that the wire format is a gap, it keeps the
 * buffer, the ownership election and the pump — which are ours and are settled —
 * testable today, and it makes adopting the real format a matter of writing one
 * class rather than unpicking assumptions from five.
 *
 * <p><strong>Before this reaches a camera</strong>, someone must capture real
 * traffic from a fielded unit or obtain the vendor's specification, and replace
 * {@link LengthPrefixedXml}. Do not treat a green test suite as evidence that the
 * protocol is right: every test here speaks the same provisional dialect as the
 * code, which proves the plumbing and nothing about the vendor.
 */
public interface LprFraming {

	/**
	 * Reads one complete message, or returns {@code null} at end of stream.
	 *
	 * @throws IOException on a malformed frame — the connection is then closed
	 *                     rather than resynchronised, because a stream whose
	 *                     framing is lost cannot be trusted to resume at a message
	 *                     boundary
	 */
	String readFrame(InputStream in) throws IOException;

	/**
	 * Writes the acknowledgement the camera waits for.
	 *
	 * <p>Called <strong>only after the capture is durably stored</strong>. The
	 * acknowledgement is a durability receipt, not a courtesy: a camera that has
	 * been told "received" will not send that capture again.
	 */
	void writeAck(OutputStream out, String eventUuid) throws IOException;

	/**
	 * ⚠️ PROVISIONAL. A four-byte big-endian length followed by that many bytes of
	 * UTF-8 XML, and an acknowledgement framed the same way.
	 *
	 * <p>Chosen because it is unambiguous and self-delimiting, which makes the rest
	 * of the ingest path testable. It is <em>a</em> documented reading of "framed
	 * XML over TCP" — not the vendor's, which nobody in this repository has.
	 */
	final class LengthPrefixedXml implements LprFraming {

		/** A frame larger than this is treated as a lost stream rather than a big message. */
		private static final int MAX_FRAME_BYTES = 1 << 20;

		@Override
		public String readFrame(InputStream in) throws IOException {
			DataInputStream data = new DataInputStream(in);
			int length;
			try {
				length = data.readInt();
			}
			catch (java.io.EOFException endOfStream) {
				return null;
			}
			if (length <= 0 || length > MAX_FRAME_BYTES) {
				throw new IOException("Refusing a frame of " + length + " bytes. Either the stream has "
						+ "lost its framing or this camera does not speak the provisional dialect in "
						+ "LprFraming — see that file before assuming the former.");
			}
			byte[] body = new byte[length];
			data.readFully(body);
			return new String(body, StandardCharsets.UTF_8);
		}

		@Override
		public void writeAck(OutputStream out, String eventUuid) throws IOException {
			byte[] ack = ("<ack><eventUuid>" + eventUuid + "</eventUuid><status>STORED</status></ack>")
					.getBytes(StandardCharsets.UTF_8);
			java.io.DataOutputStream data = new java.io.DataOutputStream(out);
			data.writeInt(ack.length);
			data.write(ack);
			data.flush();
		}
	}
}
