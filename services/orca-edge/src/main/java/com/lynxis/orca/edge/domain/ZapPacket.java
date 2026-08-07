package com.lynxis.orca.edge.domain;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * One inbound camera packet, decoded.
 *
 * <p><strong>DERIVED-FROM-1X.</strong> The element tree, the attribute names and the
 * plate-selection rule below are §2 and §4 of
 * {@code docs/lpr-wire-format-from-1x.md}, extracted from the 1.x DTOs
 * ({@code internal/dtos/xml_request.go}) and the 1.x plate selector
 * ({@code internal/utils/utils.go:227-278}). They are what the code that talks to
 * real cameras today decodes — not a vendor schema, and unknown elements are
 * ignored exactly as Go's decoder ignores them.
 *
 * <pre>
 * ZapPacket  Type@ Id@ Version@ SenderId@ SenderName@ …
 * └── Event
 *     ├── EventId · EventGuid · Online · TimeStamp · LaneId · LaneName
 *     ├── LP []                        ← one per plate hypothesis
 *     │   ├── AutoLPR                  ← the plate text
 *     │   ├── Confidence · CharConfidence
 *     │   └── LPRImage / PlateImage    ← Path references, never bytes
 *     └── Images
 * </pre>
 *
 * <p><strong>Two mappings this repository cannot settle</strong>, marked here and
 * reported rather than decided:
 *
 * <ul>
 *   <li>{@code Event/LaneId} is taken as the lane's <em>external</em> id, in core's
 *       published vocabulary. Nothing says whether the camera's lane numbering and
 *       core's external ids are the same namespace at a real site. If they are not,
 *       the mapping belongs in configuration and this is where it goes.</li>
 *   <li>{@code SenderId} is taken as the device's external id. {@code LPRImage} also
 *       carries a {@code CameraId}, and which of the two corresponds to
 *       {@code core.topology_device.device_external_id} is not recorded anywhere
 *       available here.</li>
 * </ul>
 */
public record ZapPacket(
		String id,
		String type,
		String senderId,
		String eventGuid,
		String laneId,
		String plate,
		String confidence,
		String charConfidence,
		int resultIndex,
		String timestamp) {

	/** The packet type that carries a capture. 1.x acknowledges these and nothing else. */
	public static final String MSG = "MSG";

	private static final String ROOT = "ZapPacket";

	/**
	 * Decodes one packet payload — the bytes between {@code STX} and {@code ETX}.
	 *
	 * @throws MalformedPacketException when the XML does not parse or is not a
	 *                                  {@code ZapPacket}. 1.x answers this case with
	 *                                  a {@code NAK} whose {@code Id} is
	 *                                  unavoidably empty, because the id lives in the
	 *                                  packet that failed to parse
	 */
	public static ZapPacket parse(String payload) throws MalformedPacketException {
		Element root;
		try {
			root = secureBuilder()
					.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))
					.getDocumentElement();
		}
		catch (Exception notXml) {
			throw new MalformedPacketException("The packet is not well-formed XML: " + notXml.getMessage());
		}
		if (root == null || !ROOT.equals(root.getNodeName())) {
			throw new MalformedPacketException("The packet's root element is '"
					+ (root == null ? "(none)" : root.getNodeName()) + "', not " + ROOT + ".");
		}

		Element event = firstChild(root, "Event");
		Plate winner = highestConfidencePlate(event);

		return new ZapPacket(
				attribute(root, "Id"),
				attribute(root, "Type"),
				attribute(root, "SenderId"),
				text(event, "EventGuid"),
				text(event, "LaneId"),
				winner.plate(),
				winner.confidence(),
				winner.charConfidence(),
				winner.index(),
				text(event, "TimeStamp"));
	}

	/** Whether this packet carries a capture, which is the only kind 1.x acknowledges. */
	public boolean isCapture() {
		return MSG.equals(type);
	}

	// ------------------------------------------------------------------------

	/**
	 * DERIVED-FROM-1X · §4 of the source document.
	 *
	 * <p>Among {@code Event/LP[]}, the entry with the <strong>highest numeric
	 * {@code Confidence}</strong> wins; a value that does not parse is skipped, not
	 * treated as zero. {@code resultIndex} is the winner's <em>1-based</em> position
	 * in the list, which is the numbering 1.x records and therefore the numbering an
	 * operator comparing the two systems will see.
	 *
	 * <p>A packet with no {@code LP} at all yields a blank plate. That is
	 * <strong>not</strong> a reason to drop it here: 1.x skips a plateless event and
	 * acknowledges it anyway, which is defect 1 of §5 — an event the camera believes
	 * was delivered and that exists nowhere. The buffer records what arrived and
	 * runtime decides what it means.
	 */
	private static Plate highestConfidencePlate(Element event) {
		if (event == null) {
			return Plate.NONE;
		}
		NodeList candidates = event.getElementsByTagName("LP");
		Plate best = Plate.NONE;
		double bestConfidence = Double.NEGATIVE_INFINITY;

		for (int i = 0; i < candidates.getLength(); i++) {
			Element candidate = (Element) candidates.item(i);
			String confidence = text(candidate, "Confidence");
			double numeric;
			try {
				numeric = Double.parseDouble(confidence);
			}
			catch (NumberFormatException | NullPointerException unparseable) {
				continue;
			}
			if (numeric > bestConfidence) {
				bestConfidence = numeric;
				best = new Plate(text(candidate, "AutoLPR"), confidence,
						text(candidate, "CharConfidence"), i + 1);
			}
		}
		return best;
	}

	private record Plate(String plate, String confidence, String charConfidence, int index) {

		static final Plate NONE = new Plate(null, null, null, 0);
	}

	/**
	 * A parser that reads XML and nothing else.
	 *
	 * <p>The payload arrives from a device on a site network, so entity expansion and
	 * external references are off. A camera's XML has no use for either, and a
	 * listener that resolved them would fetch whatever a compromised device asked it
	 * to.
	 */
	private static DocumentBuilder secureBuilder() throws ParserConfigurationException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		// Otherwise a malformed packet is reported on stderr and parsing continues.
		builder.setErrorHandler(null);
		return builder;
	}

	private static Element firstChild(Element parent, String name) {
		NodeList found = parent.getElementsByTagName(name);
		return found.getLength() == 0 ? null : (Element) found.item(0);
	}

	private static String text(Element parent, String name) {
		if (parent == null) {
			return null;
		}
		NodeList found = parent.getElementsByTagName(name);
		if (found.getLength() == 0) {
			return null;
		}
		Node node = found.item(0);
		String value = node.getTextContent();
		return value == null ? null : value.trim();
	}

	private static String attribute(Element element, String name) {
		String value = element.getAttribute(name);
		return value.isEmpty() ? null : value;
	}

	/** The packet did not decode. Answered with a {@code NAK}, never with a closed connection. */
	public static final class MalformedPacketException extends Exception {

		public MalformedPacketException(String message) {
			super(message);
		}
	}
}
