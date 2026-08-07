package com.lynxis.orca.runtime.integration.domain;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.integration.api.ConnectorPort;
import com.lynxis.orca.runtime.integration.domain.ConnectorTables.ConnectorConfig;
import com.lynxis.orca.runtime.integration.persistence.ConnectorConfigRepository;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The way out to a customer system: one HTTP call, with a deadline, a circuit
 * breaker and a bulkhead.
 *
 * <p><strong>What the three protect, and why one of them is not enough.</strong>
 *
 * <ul>
 *   <li><strong>The deadline</strong> bounds one call. §B8 requires every external
 *       call to have one and a defined outcome when it is exceeded; here that
 *       outcome is {@link ConnectorPort.ConnectorUnavailableException}, which the
 *       delegate turns into the process's failure branch.</li>
 *   <li><strong>The breaker</strong> bounds the <em>hundredth</em> call. A customer
 *       system that has stopped answering would otherwise be given a fresh
 *       connection and a fresh deadline by every truck that arrives, and the calls
 *       pile up faster than they time out — which is how a slow dependency becomes
 *       an unavailable gate. Open, the answer is immediate and routable.</li>
 *   <li><strong>The bulkhead</strong> bounds how much of <em>this service</em> one
 *       connector can occupy while the breaker is still deciding. Without it, the
 *       window between "slow" and "open" is enough to hold every worker thread.</li>
 * </ul>
 *
 * <p><strong>Used programmatically, not through the annotations.</strong> A breaker
 * that opens is a routing decision the process has to see, and an aspect that
 * throws from around a method makes that decision invisible at the call site. It
 * also keeps the resilience library out of every other class's imports, which is
 * what makes replacing it a change to one file.
 *
 * <p><strong>The response body is never read.</strong> §C2 keeps business data in
 * platform tables keyed by execution id and process variables to correlation keys
 * and branch discriminators; a connector that returned a body into a variable
 * would put a customer system's payload into the engine's history tables, where
 * neither retention nor scope reaches it. What comes back is a status, and the
 * status becomes a token by configuration.
 */
@Slf4j
public class RestConnector implements ConnectorPort {

	private final ConnectorConfigRepository configuration;
	private final CircuitBreakerRegistry breakers;
	private final BulkheadRegistry bulkheads;
	private final String siteExternalId;

	/**
	 * One client per connector, because the deadline is per connector and a client's
	 * timeouts are fixed when it is built.
	 *
	 * <p>Keyed by name and endpoint together, so that repointing a connector in
	 * configuration takes effect rather than being served by a cached client aimed
	 * at the old host — which would be the most confusing possible outcome of an
	 * administrator's change.
	 */
	private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

	public RestConnector(ConnectorConfigRepository configuration, CircuitBreakerRegistry breakers,
			BulkheadRegistry bulkheads, String siteExternalId) {
		this.configuration = configuration;
		this.breakers = breakers;
		this.bulkheads = bulkheads;
		this.siteExternalId = siteExternalId;
	}

	@Override
	public String call(ConnectorCall call) {
		// The delegate runs on the async executor, which has no request and therefore
		// no scope. §B6: background work establishes the installation's own scope
		// deliberately — there is no implicit entitlement for work with no user.
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(siteExternalId)),
				() -> invoke(call));
	}

	private String invoke(ConnectorCall call) {
		ConnectorConfig config = configuration.byName(call.connectorName())
				.orElseThrow(() -> new ConnectorUnavailableException(
						"Connector '" + call.connectorName() + "' is not configured, or is disabled, at "
								+ "this installation. That is a configuration gap, not a customer system "
								+ "that said no."));

		CircuitBreaker breaker = breakers.circuitBreaker(call.connectorName());
		Bulkhead bulkhead = bulkheads.bulkhead(call.connectorName());

		int status;
		try {
			status = breaker.executeSupplier(() -> bulkhead.executeSupplier(() -> send(config, call)));
		}
		catch (CallNotPermittedException breakerOpen) {
			// Not a failure of THIS call. The connector has been failing, and the
			// answer arrives immediately instead of after one more deadline.
			log.warn("connector '{}' circuit is OPEN — not calling it for visit {}",
					call.connectorName(), call.visitExternalId());
			throw new ConnectorUnavailableException("The circuit for connector '"
					+ call.connectorName() + "' is open: it has been failing, and this call was not "
					+ "attempted.", breakerOpen);
		}
		catch (BulkheadFullException saturated) {
			log.warn("connector '{}' bulkhead is full for visit {}",
					call.connectorName(), call.visitExternalId());
			throw new ConnectorUnavailableException("Connector '" + call.connectorName()
					+ "' already has its permitted number of calls in flight.", saturated);
		}
		catch (RuntimeException notReached) {
			if (notReached instanceof ConnectorUnavailableException already) {
				throw already;
			}
			throw new ConnectorUnavailableException("Connector '" + call.connectorName()
					+ "' could not be reached: " + notReached, notReached);
		}

		String outcome = configuration.outcomeFor(call.connectorName(), status)
				.orElseGet(() -> "HTTP_" + status);
		log.debug("connector '{}' answered {} for visit {} -> '{}'",
				call.connectorName(), status, call.visitExternalId(), outcome);
		return outcome;
	}

	/**
	 * One call, one status.
	 *
	 * <p>{@code exchange} rather than {@code retrieve}, deliberately: {@code retrieve}
	 * throws on a 4xx or 5xx, and here those are <em>answers</em>. A customer system
	 * that says 409 has told us something the site's own routing may well have a
	 * branch for, and turning it into an exception would discard it.
	 */
	private int send(ConnectorConfig config, ConnectorCall call) {
		RestClient client = clients.computeIfAbsent(
				config.connectorName() + "@" + config.baseUrl() + "|" + config.deadlineMillis(),
				key -> build(config));

		return client.post()
				.uri(config.requestPath())
				.contentType(MediaType.APPLICATION_JSON)
				// Correlation keys only. What the customer system needs to look this
				// truck up is the visit and the lane; anything else it needs, it asks
				// us for through the partner API (§C2).
				.body(Map.of("visitExternalId", call.visitExternalId(),
						"laneExternalId", call.laneExternalId()))
				.exchange((request, response) -> response.getStatusCode().value());
	}

	/**
	 * ⚠️ <strong>HTTP/1.1, pinned.</strong> The JDK's client defaults to attempting
	 * an HTTP/2 upgrade, and a server that does not speak it can answer by closing
	 * the connection — which arrives here as {@code EOF reached while reading} and
	 * reads exactly like an unreachable customer system. Found by running the demo
	 * against the stub, and it would have been found at a customer site otherwise.
	 *
	 * <p>A customer's Terminal Operating System is a field component of unknown
	 * vintage. Negotiating a protocol it may not speak is not a default worth
	 * having, and the version an installation needs is configuration the day one of
	 * them wants HTTP/2.
	 */
	private static RestClient build(ConnectorConfig config) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				java.net.http.HttpClient.newBuilder()
						.version(java.net.http.HttpClient.Version.HTTP_1_1)
						.connectTimeout(Duration.ofMillis(config.deadlineMillis()))
						.build());
		// Both halves of §B8's deadline. A connect timeout alone leaves a call that
		// connected and then went quiet waiting forever, which is the failure mode a
		// hung customer system actually has.
		factory.setReadTimeout(Duration.ofMillis(config.deadlineMillis()));
		return RestClient.builder()
				.requestFactory(factory)
				.baseUrl(config.baseUrl())
				.build();
	}

}
