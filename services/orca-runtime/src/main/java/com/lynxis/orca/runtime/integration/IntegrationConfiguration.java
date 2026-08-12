package com.lynxis.orca.runtime.integration;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.secrets.SecretBox;
import com.lynxis.orca.runtime.integration.api.ConnectorPort;
import com.lynxis.orca.runtime.integration.domain.ConnectorCredentialService;
import com.lynxis.orca.runtime.integration.domain.CredentialRewrapService;
import com.lynxis.orca.runtime.integration.domain.RestConnector;
import com.lynxis.orca.runtime.integration.persistence.ConnectorConfigRepository;
import com.lynxis.orca.runtime.integration.persistence.ConnectorCredentialRepository;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Wires the `integration` module — its first classes.
 *
 * <p><strong>{@link ConnectorPort} lives here, not in {@code execution}.</strong>
 * The first slice temporarily put it in {@code execution.domain} while
 * {@code integration} was empty. Now there is an adapter, and {@code ModuleWallRule} is right
 * to forbid {@code integration} from reaching into another module's {@code domain}:
 * modules talk through their {@code api} packages. The direction is the natural one
 * — {@code integration} owns what a connector <em>is</em> and publishes the
 * interface; {@code execution}'s delegate calls it.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationConfiguration {

	@Bean
	public ConnectorConfigRepository connectorConfigRepository(ScopeSeam seam) {
		return new ConnectorConfigRepository(seam);
	}

	@Bean
	public ConnectorCredentialRepository connectorCredentialRepository(ScopeSeam seam,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new ConnectorCredentialRepository(seam, siteExternalId);
	}

	@Bean
	public ConnectorCredentialService connectorCredentialService(
			ConnectorCredentialRepository repository, SecretBox secretBox,
			PlatformTransactionManager transactionManager,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new ConnectorCredentialService(repository, secretBox,
				new TransactionTemplate(transactionManager), siteExternalId);
	}

	@Bean
	public CredentialRewrapService credentialRewrapService(
			ConnectorCredentialRepository repository, SecretBox secretBox,
			PlatformTransactionManager transactionManager,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new CredentialRewrapService(repository, secretBox,
				new TransactionTemplate(transactionManager), siteExternalId);
	}

	/**
	 * ⚠️ <strong>These numbers are a local profile's answer and not a
	 * recommendation for a site.</strong> There is deliberately no universal
	 * threshold or window: a terminal operating system that is
	 * routinely slow at shift change and one that is never slow want different
	 * numbers, and choosing them belongs to whoever runs the site. They are
	 * configuration, with defaults, exactly as the lease's durations are.
	 *
	 * <p>The one thing that is not tunable is the shape: {@code COUNT_BASED} rather
	 * than time-based, because a gate that sees six trucks an hour would never fill
	 * a time window, and a breaker that never has enough data never opens.
	 */
	@Bean
	public CircuitBreakerRegistry connectorCircuitBreakers(
			@Value("${orca.runtime.connector.breaker.window:20}") int window,
			@Value("${orca.runtime.connector.breaker.failure-rate-percent:50}") float failureRate,
			@Value("${orca.runtime.connector.breaker.open-duration:30s}") Duration openDuration,
			@Value("${orca.runtime.connector.breaker.half-open-calls:3}") int halfOpenCalls) {

		return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(window)
				.minimumNumberOfCalls(window)
				.failureRateThreshold(failureRate)
				.waitDurationInOpenState(openDuration)
				.permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
				.automaticTransitionFromOpenToHalfOpenEnabled(true)
				.build());
	}

	/**
	 * How many calls one connector may have in flight.
	 *
	 * <p>{@code maxWaitDuration} is zero on purpose: a caller that cannot get a
	 * permit is refused immediately rather than queued. Queueing here would
	 * reintroduce exactly the pile-up the bulkhead exists to prevent, one level up.
	 */
	@Bean
	public BulkheadRegistry connectorBulkheads(
			@Value("${orca.runtime.connector.bulkhead.max-concurrent-calls:16}") int maxConcurrentCalls) {

		return BulkheadRegistry.of(BulkheadConfig.custom()
				.maxConcurrentCalls(maxConcurrentCalls)
				.maxWaitDuration(Duration.ZERO)
				.build());
	}

	@Bean
	public ConnectorPort connectorPort(ConnectorConfigRepository configuration,
			ConnectorCredentialRepository credentials, SecretBox secretBox,
			CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new RestConnector(configuration, credentials, secretBox,
				breakers, bulkheads, siteExternalId);
	}

	// --- the designer-authored connector, off unless somebody turns it on ------

	/**
	 * The live outbound gateway for compiled CONNECTOR nodes, off unless
	 * {@code orca.connectors.live} says otherwise. Distinct from {@link ConnectorPort}
	 * above on purpose: that is the gate process's hand-configured way out, this is
	 * the designer-authored one — different thing, configured by different people.
	 *
	 * <p>Two of its collaborators are deliberately conservative until their rulings
	 * land: the catalog is {@code UNBOUND} (where designer-authored connector
	 * configuration is stored is an open decision — a call refuses by name), and the
	 * credentials default to {@link com.lynxis.orca.runtime.integration.connector.NoAuthCredentials}
	 * — the decrypting implementation exists but stays unwired until a person has
	 * read it. Swapping either in is a one-line change made on purpose.
	 */
	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
			name = "orca.connectors.live", havingValue = "true")
	public com.lynxis.orca.runtime.execution.delegate.spi.ConnectorGateway httpConnectorGateway(
			org.springframework.beans.factory.ObjectProvider<
					com.lynxis.orca.runtime.integration.connector.ConnectorCredentials> credentials,
			com.lynxis.orca.runtime.execution.selector.SelectorDataProvider selectorDataProvider) {
		java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
				.build();
		return new com.lynxis.orca.runtime.integration.connector.HttpConnectorGateway(
				com.lynxis.orca.runtime.integration.connector.ConnectorCatalog.UNBOUND,
				credentials.getIfAvailable(
						com.lynxis.orca.runtime.integration.connector.NoAuthCredentials::new),
				http, binderFor(selectorDataProvider));
	}

	/**
	 * One evaluator per call, bound to the visit making it. Sharing a single evaluator
	 * across job threads would share its request-scoped state, and connectors run
	 * concurrently on every lane in the site. A selector the evaluator cannot resolve
	 * answers null, which drops the field — the same outcome the reference
	 * implementation reaches by logging and skipping.
	 */
	private static com.lynxis.orca.runtime.integration.connector.HttpConnectorGateway.SelectorBinder binderFor(
			com.lynxis.orca.runtime.execution.selector.SelectorDataProvider provider) {
		return executionId -> {
			var evaluator = new com.lynxis.orca.runtime.execution.selector.SelectorEvaluator(
					provider, java.time.Clock.systemUTC(), "connector");
			return (value, selectorId, visitUuid) -> {
				try {
					return evaluator.resolveSelectors(
							value, selectorId, visitUuid, (int) executionId, "", 0);
				}
				catch (Exception unresolved) {
					return null;
				}
			};
		};
	}
}
