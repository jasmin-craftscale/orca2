package com.lynxis.orca.runtime.execution.persistence;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.lynxis.orca.runtime.execution.domain.DeviceCommandPort;

import lombok.extern.slf4j.Slf4j;

/**
 * Issues a device command to the site's edge instance, over §C3's
 * {@code /internal/commands/v1}.
 *
 * <p>ADR-011: no token is minted. The per-installation shared credential goes on
 * the request, and {@code X-Orca-Service} is attribution only — a shared credential
 * cannot prove which peer is calling. Token <em>issuing</em> would put the identity
 * provider on the gate path, and §A1 says the gate must keep working when other
 * things do not.
 *
 * <h2>The mapping that matters</h2>
 *
 * <p><strong>A transport failure is {@code UNKNOWN}, not {@code FAILED}.</strong>
 * That is the single most important line in this class. If the request timed out,
 * or the connection dropped after it was sent, edge may already have raised the
 * barrier — and telling the process the command failed would state, as fact, that a
 * physical thing did not happen when it may well have. §B10 resolves an unknown
 * outcome by verifying the device, never by retrying and never by assuming.
 *
 * <p>{@code IN_PROGRESS} maps to {@code UNKNOWN} for the same reason and not
 * because they mean the same thing: another delivery is mid-way through, so the
 * physical outcome is not yet decided, and the branch that <em>looks</em> is the
 * only correct next step.
 *
 * <p>An outcome edge could reach and refuse — a host that answered with an error, a
 * command discarded as expired — comes back {@code FAILED}, because in both of
 * those cases the barrier is known not to have moved.
 *
 * <p>It lives in {@code persistence} beside {@code FlowableProcessEngineGateway}
 * rather than in a fifth package: the module's convention is api / domain /
 * persistence, and this is an outbound adapter of exactly the same kind as the
 * engine one. Recorded rather than assumed, because "persistence" is a poor name
 * for it.
 */
@Slf4j
public class EdgeDeviceCommandClient implements DeviceCommandPort {

	/**
	 * How much longer than the command's own deadline this client waits.
	 *
	 * <p>Edge checks the deadline itself and answers within it, so a transport
	 * timeout at exactly the deadline would race edge's own answer and turn a
	 * knowable {@code FAILED} into an {@code UNKNOWN} — which costs a physical
	 * verification nobody needed.
	 */
	private static final long TRANSPORT_GRACE_MILLIS = 2_000L;

	private final String edgeBaseUrl;
	private final String sharedCredential;

	public EdgeDeviceCommandClient(String edgeBaseUrl, String sharedCredential) {
		this.edgeBaseUrl = edgeBaseUrl;
		this.sharedCredential = sharedCredential;
	}

	@Override
	public String issue(DeviceCommand command) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("commandId", command.commandId());
		body.put("laneExternalId", command.laneExternalId());
		body.put("action", command.action());
		body.put("deadlineMs", command.deadlineMillis());

		try {
			Map<?, ?> answer = client(command.deadlineMillis()).post()
					.uri("/internal/commands/v1")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(Map.class);

			return outcomeOf(command, answer);
		}
		catch (Exception noAnswer) {
			// UNKNOWN, and this is the line to read twice. Edge may already have
			// raised the barrier; saying FAILED would state as fact that a physical
			// thing did not happen when it may well have.
			log.warn("edge did not answer command {} for lane {} ({}). The physical outcome is "
							+ "UNKNOWN and must be verified, not retried.",
					command.commandId(), command.laneExternalId(), noAnswer.toString());
			return UNKNOWN;
		}
	}

	private static String outcomeOf(DeviceCommand command, Map<?, ?> answer) {
		Object data = answer == null ? null : answer.get("data");
		Object status = data instanceof Map<?, ?> payload ? payload.get("status") : null;
		if (status == null) {
			// The envelope arrived and did not carry an outcome. Edge acted or it did
			// not, and this cannot tell which.
			log.warn("edge's answer to command {} carried no status: {}", command.commandId(), answer);
			return UNKNOWN;
		}

		String reported = status.toString();
		if (EXECUTED.equals(reported) || FAILED.equals(reported)) {
			return reported;
		}
		// IN_PROGRESS, or a value this version does not know. Both mean the physical
		// outcome is not decided, which is what UNKNOWN is for.
		log.info("edge reports '{}' for command {} — treated as UNKNOWN, so the process verifies "
				+ "the device rather than assuming", reported, command.commandId());
		return UNKNOWN;
	}

	/**
	 * A client per call, because the deadline is per command.
	 *
	 * <p>The read timeout is the command's deadline plus a grace: edge enforces the
	 * deadline itself, so timing out at exactly the deadline would race its answer.
	 */
	private RestClient client(long deadlineMillis) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
		factory.setReadTimeout(Duration.ofMillis(deadlineMillis + TRANSPORT_GRACE_MILLIS));
		return RestClient.builder()
				.requestFactory(factory)
				.baseUrl(edgeBaseUrl)
				.defaultHeader("X-Orca-Internal-Auth", sharedCredential)
				.defaultHeader("X-Orca-Service", "orca-runtime")
				.build();
	}
}
