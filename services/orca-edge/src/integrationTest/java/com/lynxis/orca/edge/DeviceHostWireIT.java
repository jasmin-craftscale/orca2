package com.lynxis.orca.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.lynxis.orca.edge.domain.DeviceHostPort;
import com.lynxis.orca.edge.domain.RestDeviceHost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * <strong>What actually goes on the wire to a device host.</strong>
 *
 * <p>Every assertion here is against {@code docs/device-host-outbound-from-1x.md},
 * extracted from the ORCA 1.x production caller. The suite exists because the
 * previous contract was <em>invented</em> and had a green test suite: every test
 * spoke the same invented dialect as the code, so the tests agreed with the code
 * about something neither had any evidence for.
 *
 * <p>These tests are therefore written against a <strong>real socket</strong> and
 * assert the bytes — method, path, query string, headers, body — rather than against
 * the class's own abstractions. A test that called {@code RestDeviceHost} and checked
 * its return value would pass just as happily on the wrong URL.
 *
 * <p>⚠️ <strong>What this suite cannot prove:</strong> that the extraction is what
 * the vendor specifies. It proves this repository speaks what the fielded estate
 * speaks, which is a strictly better claim than the one it replaces and is still not
 * a vendor document.
 */
class DeviceHostWireIT {

	private static final String DEVICE = "0f5c2a91-barrier";

	private HttpServer host;
	private final List<Received> received = new CopyOnWriteArrayList<>();
	private int status = 200;
	private String answer = """
			{"status":"OK","code":200,"message":"performed","request_id":"r-1",\
			"timestamp":"2026-08-07T00:00:00Z"}""";

	@BeforeEach
	void startHost() throws Exception {
		host = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		host.createContext("/", exchange -> {
			received.add(Received.of(exchange));
			byte[] body = answer.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		host.start();
	}

	@AfterEach
	void stopHost() {
		host.stop(0);
	}

	// ------------------------------------------------------------------------
	// The three legacy 1.x calls and their routing asymmetry.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("RAISE_GATE and LOWER_GATE POST /api/{device}/raiseGate|lowerGate with an EMPTY body")
	void theGateCallsAddressTheDeviceUnderApiAndSendNothing() {
		assertThat(issue("RAISE_GATE", null).status()).isEqualTo(DeviceHostPort.Outcome.EXECUTED);
		assertThat(issue("LOWER_GATE", null).status()).isEqualTo(DeviceHostPort.Outcome.EXECUTED);

		assertThat(received).hasSize(2);
		assertThat(received.get(0).method()).isEqualTo("POST");
		assertThat(received.get(0).path()).isEqualTo("/api/" + DEVICE + "/raiseGate");
		assertThat(received.get(1).path()).isEqualTo("/api/" + DEVICE + "/lowerGate");

		assertThat(received).allSatisfy(call -> {
			assertThat(call.body()).as("the gate calls carry no body at all").isEmpty();
			assertThat(call.contentType())
					.as("1.x sets application/json DESPITE the empty body. Reproduced, not tidied")
					.isEqualTo("application/json");
		});
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("PRINT POSTs the file's RAW BYTES to /api/{device}/print/{format} with NO Content-Type")
	void printSendsRawBytesAndDeliberatelyNoContentType() {
		byte[] file = { 0x1B, '@', 'T', 'I', 'C', 'K', 'E', 'T', 0x0A };
		String params = "{\"format\":\"escpos\",\"content\":\""
				+ Base64.getEncoder().encodeToString(file) + "\"}";

		assertThat(issue("PRINT", params).status()).isEqualTo(DeviceHostPort.Outcome.EXECUTED);

		assertThat(received).hasSize(1);
		Received call = received.get(0);
		assertThat(call.path()).isEqualTo("/api/" + DEVICE + "/print/escpos");
		assertThat(call.body())
				.as("the upstream base64 is DECODED before sending — the host receives the file, "
						+ "not a description of it")
				.isEqualTo(file);
		assertThat(call.contentType())
				.as("""
						THE POINT OF THIS ASSERTION: 1.x has the Content-Type commented out \
						deliberately, and a .NET host of unknown vintage is not the place to \
						discover that application/octet-stream changes how a payload is read. \
						Spring's own byte[] converter would have added one.""")
				.isNull();
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("SET_IO addresses /api/io/{device}/… — the asymmetry is faithful, not a typo")
	void ioIsAddressedUnderApiIoAndCarriesTheStateInThePath() {
		status = 200;
		assertThat(issue("SET_IO",
				"{\"ioPort\":\"3\",\"state\":true,\"ioPortName\":\"loop detector A\"}").status())
				.isEqualTo(DeviceHostPort.Outcome.EXECUTED);

		assertThat(received).hasSize(1);
		Received call = received.get(0);
		assertThat(call.path())
				.as("gate and print address /api/{device}/…; IO addresses /api/io/{device}/… . "
						+ "A tidier scheme would be a different contract, and the other side of this "
						+ "one is a fielded component")
				.isEqualTo("/api/io/" + DEVICE + "/3/true");
		assertThat(call.query())
				.as("the port NAME travels as a url-escaped query parameter, not as a path segment")
				.isEqualTo("ioPortName=loop+detector+A");
		assertThat(call.body()).isEmpty();
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("a false SET_IO ends in 'false' — the state is a path segment, not a body field")
	void aFalseIoStateIsInThePath() {
		issue("SET_IO", "{\"ioPort\":\"3\",\"state\":false}");

		assertThat(received).hasSize(1);
		assertThat(received.get(0).path()).isEqualTo("/api/io/" + DEVICE + "/3/false");
		assertThat(received.get(0).query())
				.as("no ioPortName supplied, so no query parameter invented for it")
				.isNull();
	}

	// ------------------------------------------------------------------------
	// Success is HTTP 200 AND a body that decodes.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("200 with a body that is not JSON is FAILED — an acknowledgement is not a confirmation")
	void a200WithAnUndecodableBodyIsNotAnExecution() {
		answer = "<html>proxy interposed</html>";

		DeviceHostPort.Outcome outcome = issue("RAISE_GATE", null);

		assertThat(outcome.status()).isEqualTo(DeviceHostPort.Outcome.FAILED);
		assertThat(outcome.deviceResponse())
				.as("the host's own words are recorded, because after an incident the platform's "
						+ "reading of the answer is worth less than the answer")
				.contains("proxy interposed");
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("a non-200 is FAILED even when its body decodes — 1.x compares against 200, not 2xx")
	void a204IsAFailureEvenWithAGoodBody() {
		status = 202;

		assertThat(issue("RAISE_GATE", null).status())
				.as("""
						1.x collapses every non-200 into one failure. Widening this to 2xx here \
						would be this repository deciding, on the vendor's behalf, that an \
						'accepted' means a barrier moved.""")
				.isEqualTo(DeviceHostPort.Outcome.FAILED);
	}

	// ------------------------------------------------------------------------
	// What the extraction does NOT cover, and what this class does about it.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("PTZ_PRESET has no route in the 1.x extraction — it is REFUSED, and nothing is sent")
	void anActionWithNoExtractedRouteIsRefusedRatherThanGuessedAt() {
		DeviceHostPort.Outcome outcome = issue("PTZ_PRESET", "{\"preset\":4}");

		assertThat(outcome.status()).isEqualTo(DeviceHostPort.Outcome.FAILED);
		assertThat(outcome.detail())
				.as("§C3 names five actions and the 1.x caller has three calls. The gap is REPORTED "
						+ "in the outcome an operator reads, not closed by inventing a URL")
				.contains("no device-host route is recorded");
		assertThat(received)
				.as("THE POINT OF THIS TEST: zero calls. A POST at a guessed URL on a machine that "
						+ "moves barriers is worse than a refusal")
				.isEmpty();
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("a PRINT with no format, and a SET_IO with no state, are refused before anything is sent")
	void aCommandThatCannotAddressTheHostIsRefusedBeforeTheSocketOpens() {
		assertThat(issue("PRINT", "{\"content\":\"AAA=\"}").detail()).contains("no 'format'");
		assertThat(issue("PRINT", "{\"format\":\"escpos\"}").detail()).contains("no base64 'content'");
		assertThat(issue("SET_IO", "{\"ioPort\":\"3\"}").detail()).contains("no boolean 'state'");

		assertThat(received).isEmpty();
	}

	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES)
	@DisplayName("no Authorization header is sent — the slot is an OPEN QUESTION, register NEW-4")
	void nothingAuthenticatesThisCallAndThatIsDeliberate() {
		issue("RAISE_GATE", null);

		assertThat(received.get(0).authorization())
				.as("""
						1.x mints a Keycloak token per device-host command. Whether the .NET host \
						VALIDATES it cannot be determined from this repository, and if it does, the \
						barrier path acquires an identity-provider dependency that §A1 forbids. \
						This assertion exists so that filling the slot in is a deliberate act with \
						a failing test attached, not a quiet commit.""")
				.isNull();
	}

	// ------------------------------------------------------------------------

	private DeviceHostPort.Outcome issue(String action, String params) {
		return new RestDeviceHost().issue("http://localhost:" + host.getAddress().getPort(),
				new DeviceHostPort.HostCommand("cmd-" + action, DEVICE, action, params, 5_000L));
	}

	/** One request as the host actually received it. Bytes, not the caller's intent. */
	private record Received(String method, String path, String query, String contentType,
			String authorization, byte[] body) {

		static Received of(HttpExchange exchange) {
			try {
				return new Received(exchange.getRequestMethod(),
						exchange.getRequestURI().getPath(),
						exchange.getRequestURI().getQuery(),
						exchange.getRequestHeaders().getFirst("Content-Type"),
						exchange.getRequestHeaders().getFirst("Authorization"),
						exchange.getRequestBody().readAllBytes());
			}
			catch (java.io.IOException unreadable) {
				throw new IllegalStateException(unreadable);
			}
		}
	}
}
