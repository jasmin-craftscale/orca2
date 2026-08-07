package com.lynxis.orca.edge.domain;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * ⚠️ PROVISIONAL, with {@link DeviceHostPort}. One POST per command, JSON in and
 * JSON out.
 *
 * <p>It is <em>a</em> documented reading of §D2's "device-host REST … gate, print
 * and generic IO commands outbound" — not the vendor's, which nobody in this
 * repository has. Every field name below was chosen here.
 *
 * <p>What is <strong>not</strong> provisional is the outcome vocabulary, and that
 * is the part worth reading. §C3: <em>a command completes when the device host
 * confirms it acted — an acknowledgement AND a body that decodes.</em> So:
 *
 * <ul>
 *   <li>A 2xx whose body says the device acted → {@code EXECUTED}.</li>
 *   <li>A 2xx whose body does not decode, or says the device refused → {@code FAILED}.
 *       The host answered; what it answered was not an execution.</li>
 *   <li>A non-2xx → {@code FAILED}. The host is there and said no.</li>
 *   <li>A timeout, or a connection that never completed → {@code UNKNOWN}. Nobody
 *       knows whether the barrier moved, and the only correct next step is to look
 *       (§B10). Coercing this to {@code FAILED} would tell the gate a barrier did
 *       not rise when it did.</li>
 * </ul>
 */
@Slf4j
public class RestDeviceHost implements DeviceHostPort {

	/** ⚠️ PROVISIONAL. The route this implementation posts to. */
	static final String COMMAND_PATH = "/api/v1/commands";

	@Override
	public Outcome issue(String deviceHostUrl, HostCommand command) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("commandId", command.commandId());
		body.put("deviceExternalId", command.deviceExternalId());
		body.put("action", command.action());
		body.put("params", command.params());

		try {
			RestClient host = client(deviceHostUrl, command.deadlineMillis());
			return host.post()
					.uri(COMMAND_PATH)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.exchange((request, response) -> {
						String answer = new String(response.getBody().readAllBytes(),
								java.nio.charset.StandardCharsets.UTF_8);
						if (!response.getStatusCode().is2xxSuccessful()) {
							// The host is there and said no. That is a FAILURE, and it is
							// knowable — quite unlike a silence.
							return Outcome.failed(answer,
									"the device host answered " + response.getStatusCode().value());
						}
						return decode(answer);
					});
		}
		catch (Exception noAnswer) {
			// A timeout, a reset connection, a host that accepted the request and then
			// went quiet. §B10: the physical outcome is UNKNOWN and must be verified,
			// never retried and never assumed to have failed.
			if (isTimeout(noAnswer)) {
				log.warn("device host {} did not answer command {} within {} ms — outcome UNKNOWN",
						deviceHostUrl, command.commandId(), command.deadlineMillis());
				return Outcome.unknown("the device host did not answer within "
						+ command.deadlineMillis() + " ms");
			}
			log.warn("device host {} could not be reached for command {}: {}",
					deviceHostUrl, command.commandId(), noAnswer.toString());
			// Not reached at all. The command never left, so nothing physical can have
			// happened — which makes this knowable, and FAILED rather than UNKNOWN.
			return Outcome.failed(null, "the device host could not be reached: " + noAnswer);
		}
	}

	/**
	 * ⚠️ PROVISIONAL. {@code {"status":"OK"}} is an execution; anything else is not.
	 *
	 * <p>Deliberately strict. §C3 says a command completes when the host confirms it
	 * <em>acted</em>, so a body this cannot read is not a confirmation — and reading
	 * an unrecognised body optimistically is how "the barrier rose" comes to mean
	 * "the barrier was asked to".
	 */
	private static Outcome decode(String answer) {
		if (answer != null && answer.contains("\"status\"") && answer.contains("\"OK\"")) {
			return Outcome.executed(answer);
		}
		return Outcome.failed(answer, "the device host's response did not decode as an execution");
	}

	private static boolean isTimeout(Throwable thrown) {
		for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
			if (cause instanceof SocketTimeoutException
					|| cause instanceof java.net.http.HttpTimeoutException
					|| cause instanceof java.util.concurrent.TimeoutException) {
				return true;
			}
			if (cause == cause.getCause()) {
				break;
			}
		}
		return false;
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
	private static RestClient client(String deviceHostUrl, long deadlineMillis) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				java.net.http.HttpClient.newBuilder()
						.version(java.net.http.HttpClient.Version.HTTP_1_1)
						.connectTimeout(Duration.ofMillis(deadlineMillis))
						.build());
		factory.setReadTimeout(Duration.ofMillis(deadlineMillis));
		return RestClient.builder().requestFactory(factory).baseUrl(deviceHostUrl).build();
	}
}
