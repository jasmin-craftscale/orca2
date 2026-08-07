package com.lynxis.orca.edge.domain;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The outbound half of the device-host contract, as the fielded estate speaks it.
 *
 * <p><strong>DERIVED-FROM-1X.</strong> Every route, every body and the whole
 * success rule below comes from {@code docs/device-host-outbound-from-1x.md}, which
 * was extracted from the ORCA 1.x production caller — the Go service that commands
 * real barriers today. It is <em>not</em> the vendor's specification, which nobody
 * in this repository has and which remains worth obtaining.
 *
 * <p>It supersedes the invented dialect this class shipped with in WP7 — one
 * {@code POST /api/v1/commands} with a JSON envelope — which
 * {@code phase-1-report.md} §3 recorded as a guess. <strong>That guess was wrong in
 * every particular: the route, the body, the method of addressing the device and the
 * success rule.</strong>
 *
 * <h2>The three calls</h2>
 *
 * <pre>
 * RAISE_GATE  POST {host}/api/{device}/raiseGate                       empty body
 * LOWER_GATE  POST {host}/api/{device}/lowerGate                       empty body
 * PRINT       POST {host}/api/{device}/print/{format}                  raw file bytes
 * SET_IO      POST {host}/api/io/{device}/{port}/{true|false}?ioPortName=…   empty body
 * </pre>
 *
 * <p><strong>The {@code /api/io/} asymmetry is faithful, not a typo.</strong> Gate
 * and print address {@code /api/{device}/…}; IO addresses {@code /api/io/{device}/…}.
 * A tidier scheme would be a different contract, and the other side of this one is a
 * fielded component.
 *
 * <h2>What counts as success</h2>
 *
 * <p>1.x treats a command as performed on <strong>HTTP 200 and a body that
 * decodes</strong> as its answer document —
 * {@code {status, code, message, request_id, timestamp}}. Anything else is a
 * failure. That is §C3's rule — <em>an acknowledgement <strong>and</strong> a body
 * that decodes</em> — already fielded, so the 2.0 outcome vocabulary maps onto it
 * without interpretation:
 *
 * <ul>
 *   <li>200 with a decodable body → {@code EXECUTED}.</li>
 *   <li>200 with a body that does not decode → {@code FAILED}. The host answered;
 *       what it answered was not a confirmation.</li>
 *   <li>Any other status → {@code FAILED}. Note <strong>200 exactly</strong>, not
 *       2xx: 1.x compares against 200 and collapses everything else into one
 *       failure, so a 204 is a failure here too.</li>
 *   <li>No answer inside the deadline → {@code UNKNOWN}. Nobody knows whether the
 *       barrier moved, and the only correct next step is to look (§B10). Coercing
 *       this to {@code FAILED} would tell the gate a barrier did not rise when it
 *       did.</li>
 *   <li>A connection that was never established → {@code FAILED}. The request never
 *       left, so nothing physical can have happened — knowable, unlike a silence.
 *       This is why a connect timeout is separated from a response timeout below;
 *       the JDK makes {@link HttpConnectTimeoutException} a subtype of
 *       {@link HttpTimeoutException} and the two mean opposite things here.</li>
 * </ul>
 *
 * <h2>⚠️ The Authorization header is deliberately absent — OPEN QUESTION, register NEW-4</h2>
 *
 * <p>1.x mints a Keycloak token for every device-host command and presents it as
 * {@code Bearer} (§3 of the source document). <strong>Whether the .NET host
 * validates that token cannot be determined from this repository</strong>, and the
 * two answers have opposite consequences: if it is ignored, the header is cargo; if
 * it is enforced, the barrier path acquires an identity-provider dependency that §A1
 * forbids and ADR-011 rules out, and 1.x cannot open a gate today while Keycloak is
 * unreachable. Only the vendor, the host's configuration, or a test against a real
 * host can answer it, and the choice between the remedies is the product owner's.
 *
 * <p>So the slot is marked and left empty — see {@link #openQuestionAuthorization}.
 * <strong>Do not fill it in without the ruling.</strong>
 *
 * <h2>What is NOT derived, and is a choice made here</h2>
 *
 * <p>The source document records the wire, not the upstream message 1.x built it
 * from, so the <em>parameter names</em> this class reads out of {@code params} were
 * chosen here and are marked {@code CHOSEN-HERE} at {@link #route}. They are ours to
 * change; the routes are not.
 *
 * @see <a href="file:../../../../../../../../../docs/device-host-outbound-from-1x.md">docs/device-host-outbound-from-1x.md</a>
 */
@Slf4j
public class RestDeviceHost implements DeviceHostPort {

	/** §C3's action vocabulary. Four of the five have a route in the 1.x extraction. */
	static final String RAISE_GATE = "RAISE_GATE";
	static final String LOWER_GATE = "LOWER_GATE";
	static final String PRINT = "PRINT";
	static final String SET_IO = "SET_IO";

	private static final ObjectMapper JSON = new ObjectMapper();

	@Override
	public Outcome issue(String deviceHostUrl, HostCommand command) {
		HttpRequest request;
		try {
			// The deadline bounds the WAIT, not just the connect. Without this the JDK
			// waits forever for a host that accepted the request and went quiet, and
			// the deadline the process set would mean nothing.
			request = route(deviceHostUrl, command)
					.timeout(Duration.ofMillis(command.deadlineMillis()))
					.build();
		}
		catch (UnroutableCommand unroutable) {
			// Nothing was sent and nothing can be. FAILED rather than UNKNOWN: the
			// physical state of the device is not in question, the command is.
			log.warn("command {} ({}) was not sent to {}: {}", command.commandId(), command.action(),
					deviceHostUrl, unroutable.getMessage());
			return Outcome.failed(null, unroutable.getMessage());
		}

		try {
			HttpResponse<byte[]> response = client(command.deadlineMillis())
					.send(request, HttpResponse.BodyHandlers.ofByteArray());
			String answer = new String(response.body(), StandardCharsets.UTF_8);

			if (response.statusCode() != 200) {
				return Outcome.failed(answer,
						"the device host answered " + response.statusCode());
			}
			if (!decodesAsHostAnswer(answer)) {
				return Outcome.failed(answer,
						"the device host answered 200 with a body that is not its answer document");
			}
			return Outcome.executed(answer);
		}
		catch (HttpConnectTimeoutException neverConnected) {
			// Checked BEFORE HttpTimeoutException, which it extends. The connection was
			// never made, so the command never left this process.
			log.warn("device host {} could not be connected to for command {} within {} ms",
					deviceHostUrl, command.commandId(), command.deadlineMillis());
			return Outcome.failed(null, "the device host could not be connected to within "
					+ command.deadlineMillis() + " ms. Nothing was sent.");
		}
		catch (HttpTimeoutException noAnswer) {
			// The request left. The barrier may be rising right now.
			log.warn("device host {} did not answer command {} within {} ms — outcome UNKNOWN",
					deviceHostUrl, command.commandId(), command.deadlineMillis());
			return Outcome.unknown("the device host did not answer within "
					+ command.deadlineMillis() + " ms");
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			// The wait was abandoned, not the request. Whether the host acted is
			// exactly as unknown as it is after a timeout.
			return Outcome.unknown("the wait for the device host was interrupted");
		}
		catch (IOException | RuntimeException notReached) {
			log.warn("device host {} could not be reached for command {}: {}", deviceHostUrl,
					command.commandId(), notReached.toString());
			return Outcome.failed(null, "the device host could not be reached: " + notReached);
		}
	}

	// ------------------------------------------------------------------------
	// The routes. DERIVED-FROM-1X · §1 of the source document.
	// ------------------------------------------------------------------------

	/**
	 * Builds the one request this action makes, or refuses to build one.
	 *
	 * <p><strong>CHOSEN-HERE:</strong> the {@code params} keys — {@code format},
	 * {@code content} for a print; {@code ioPort}, {@code state}, {@code ioPortName}
	 * for an IO command. The source document records the wire and not the upstream
	 * message 1.x assembled it from, so these names are this repository's and can be
	 * changed by whoever authors the process; the routes they feed cannot.
	 *
	 * @throws UnroutableCommand when this class cannot address the device host for
	 *                           this command. It is thrown rather than guessed at,
	 *                           because the alternative is a POST at an invented URL
	 *                           on a machine that moves barriers
	 */
	private static HttpRequest.Builder route(String deviceHostUrl, HostCommand command) {
		String base = deviceHostUrl == null ? "" : deviceHostUrl.replaceAll("/+$", "");
		String device = command.deviceExternalId();
		if (device == null || device.isBlank()) {
			throw new UnroutableCommand("the device host addresses the device in the URL path and "
					+ "this command carries no device external id");
		}

		HttpRequest.Builder request = switch (command.action() == null ? "" : command.action()) {
			case RAISE_GATE -> HttpRequest.newBuilder(java.net.URI.create(
							base + "/api/" + segment(device) + "/raiseGate"))
					// The empty body still carries this in 1.x. Reproduced, not tidied.
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.noBody());
			case LOWER_GATE -> HttpRequest.newBuilder(java.net.URI.create(
							base + "/api/" + segment(device) + "/lowerGate"))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.noBody());
			case PRINT -> printRequest(base, device, command);
			case SET_IO -> ioRequest(base, device, command);
			default -> throw new UnroutableCommand("no device-host route is recorded for action '"
					+ command.action() + "'. The 1.x extraction covers the gate, print and IO calls "
					+ "and nothing else — see docs/device-host-outbound-from-1x.md §1. Sending this "
					+ "at a guessed URL would be a guess made against hardware");
		};

		openQuestionAuthorization(request);
		return request;
	}

	/**
	 * Print — the one call with a body, and the one that sends <strong>no</strong>
	 * {@code Content-Type}.
	 *
	 * <p>1.x has the header commented out deliberately, and a .NET host of unknown
	 * vintage is not the place to discover that {@code application/octet-stream}
	 * changes how a payload is read. The body is the raw file bytes: the upstream
	 * base64 is decoded here, exactly as 1.x decodes it before sending.
	 *
	 * <p>⚠️ The source document also notes an {@code encoding} field defaulting to
	 * {@code binary}, whose semantics are <strong>not recoverable</strong> from the
	 * extraction. It is not implemented, and that gap is reported rather than
	 * guessed: {@code content} is read as base64 and nothing else.
	 */
	private static HttpRequest.Builder printRequest(String base, String device, HostCommand command) {
		JsonNode params = params(command);
		String format = text(params, "format");
		if (format == null) {
			throw new UnroutableCommand("a PRINT addresses /api/{device}/print/{format} and this "
					+ "command's params carry no 'format'");
		}
		String content = text(params, "content");
		if (content == null) {
			throw new UnroutableCommand("a PRINT sends the file's raw bytes as its body and this "
					+ "command's params carry no base64 'content'");
		}
		byte[] file;
		try {
			file = Base64.getDecoder().decode(content);
		}
		catch (IllegalArgumentException notBase64) {
			throw new UnroutableCommand("this command's params carry a 'content' that is not base64, "
					+ "so the file's raw bytes cannot be recovered");
		}
		return HttpRequest.newBuilder(java.net.URI.create(
						base + "/api/" + segment(device) + "/print/" + segment(format)))
				// NO Content-Type. See the Javadoc — this is 1.x's deliberate omission.
				.POST(HttpRequest.BodyPublishers.ofByteArray(file));
	}

	/**
	 * IO — {@code /api/io/{device}/{port}/{true|false}?ioPortName={name}}, empty body.
	 *
	 * <p>⚠️ <strong>CHOSEN-HERE: no {@code Content-Type}.</strong> The source
	 * document records the header for the gate call and its absence for the print
	 * call, and says nothing about this one. Asserting {@code application/json} here
	 * because the gate call sends it would be reasoning about a vendor's component
	 * from a neighbour, so this sends none and the gap is reported.
	 */
	private static HttpRequest.Builder ioRequest(String base, String device, HostCommand command) {
		JsonNode params = params(command);
		String port = text(params, "ioPort");
		if (port == null) {
			throw new UnroutableCommand("a SET_IO addresses /api/io/{device}/{port}/{state} and this "
					+ "command's params carry no 'ioPort'");
		}
		JsonNode state = params.get("state");
		if (state == null || !(state.isBoolean() || isBooleanText(state))) {
			throw new UnroutableCommand("a SET_IO's URL ends in 'true' or 'false' and this command's "
					+ "params carry no boolean 'state'");
		}
		String url = base + "/api/io/" + segment(device) + "/" + segment(port) + "/"
				+ (state.isBoolean() ? state.booleanValue() : Boolean.parseBoolean(state.asText()));

		String portName = text(params, "ioPortName");
		if (portName != null) {
			url += "?ioPortName=" + URLEncoder.encode(portName, StandardCharsets.UTF_8);
		}
		return HttpRequest.newBuilder(java.net.URI.create(url))
				.POST(HttpRequest.BodyPublishers.noBody());
	}

	/**
	 * ⚠️ <strong>The Authorization header's slot. Deliberately empty — OPEN QUESTION,
	 * register NEW-4.</strong>
	 *
	 * <p>This method exists so the question has an address in the code rather than
	 * only in a document. 1.x sends {@code Authorization: Bearer <keycloak token>} on
	 * all three calls; whether the host validates it is unknown, and filling this in
	 * either way settles a question that belongs to the product owner and the vendor.
	 * See the class Javadoc and §3 of {@code docs/device-host-outbound-from-1x.md}.
	 */
	@SuppressWarnings("unused")
	private static void openQuestionAuthorization(HttpRequest.Builder request) {
		// Intentionally no header. Do not implement without the ruling.
	}

	// ------------------------------------------------------------------------
	// The answer. DERIVED-FROM-1X · §2 of the source document.
	// ------------------------------------------------------------------------

	/**
	 * Whether the host's body decodes as its answer document.
	 *
	 * <p><strong>Faithful to 1.x, deliberately, including its looseness.</strong> The
	 * 1.x caller unmarshals the body into a struct of five fields, and Go's decoder
	 * accepts any JSON <em>object</em> there — including {@code {}} — while rejecting
	 * an array, a scalar and anything that is not JSON at all. So that is the test:
	 * <strong>a JSON object</strong>.
	 *
	 * <p>Requiring the five fields would be stricter than the fielded caller and
	 * would risk reporting {@code FAILED} for a barrier that rose, which is the exact
	 * hazard §B10 exists to prevent. The cost of the looseness is stated rather than
	 * fixed: a proxy that answers {@code 200 {"error":"..."}} would read as an
	 * execution here, as it does in 1.x today.
	 */
	private static boolean decodesAsHostAnswer(String answer) {
		if (answer == null || answer.isBlank()) {
			return false;
		}
		try {
			return JSON.readTree(answer).isObject();
		}
		catch (com.fasterxml.jackson.core.JacksonException notJson) {
			return false;
		}
	}

	// ------------------------------------------------------------------------

	private static JsonNode params(HostCommand command) {
		if (command.params() == null || command.params().isBlank()) {
			return JSON.createObjectNode();
		}
		try {
			JsonNode parsed = JSON.readTree(command.params());
			return parsed.isObject() ? parsed : JSON.createObjectNode();
		}
		catch (com.fasterxml.jackson.core.JacksonException notJson) {
			throw new UnroutableCommand("this command's params are not a JSON object, so the "
					+ "device-host URL cannot be built from them");
		}
	}

	private static String text(JsonNode params, String field) {
		JsonNode value = params.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText();
		return text.isBlank() ? null : text;
	}

	private static boolean isBooleanText(JsonNode state) {
		return state.isTextual()
				&& ("true".equalsIgnoreCase(state.asText()) || "false".equalsIgnoreCase(state.asText()));
	}

	/** Percent-encodes one path segment. A device id is a token; this is the seatbelt. */
	private static String segment(String raw) {
		return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * A client per call, because the deadline is per command.
	 *
	 * <p>Not cached: §C3's actions carry their own deadlines and two commands to one
	 * host can legitimately want different ones. A barrier is a handful of calls a
	 * minute, so the cost of a fresh client is not the thing to optimise.
	 *
	 * <p>⚠️ <strong>HTTP/1.1, pinned.</strong> The JDK's client attempts an HTTP/2
	 * upgrade by default, and a server that does not speak it can answer by closing
	 * the connection — which would arrive here as an unreachable device host. A .NET
	 * device host is a field-proven component of unknown vintage (§D2); negotiating a
	 * protocol it may not speak is not a default worth having on a barrier.
	 */
	private static HttpClient client(long deadlineMillis) {
		return HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofMillis(deadlineMillis))
				.build();
	}

	/**
	 * This class cannot address the device host for this command.
	 *
	 * <p>Not an error the caller handles — {@link #issue} turns it into a
	 * {@code FAILED} outcome with the reason in {@code detail}, because a command
	 * that was never sent is an outcome an operator can read.
	 */
	private static final class UnroutableCommand extends RuntimeException {

		private UnroutableCommand(String why) {
			super(why);
		}
	}
}
