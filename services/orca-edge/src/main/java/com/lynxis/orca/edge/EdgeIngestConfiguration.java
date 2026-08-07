package com.lynxis.orca.edge;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxis.orca.edge.domain.DeliveryPump;
import com.lynxis.orca.edge.domain.IngestTasks;
import com.lynxis.orca.edge.domain.LaneOwnership;
import com.lynxis.orca.edge.domain.LprFraming;
import com.lynxis.orca.edge.domain.LprListener;
import com.lynxis.orca.edge.persistence.EventBufferRepository;
import com.lynxis.orca.platform.lease.FencedWrite;
import com.lynxis.orca.platform.lease.LeaseManager;
import com.lynxis.orca.platform.scope.ScopeSeam;

import lombok.extern.slf4j.Slf4j;

/** Wires edge's ingest path: the buffer, the per-lane election, the pump and the listener. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class EdgeIngestConfiguration {

	@Bean
	public EventBufferRepository eventBufferRepository(ScopeSeam seam) {
		return new EventBufferRepository(seam);
	}

	/**
	 * @param holderId this instance's identity in the lease table. Defaults to the
	 *                 hostname, which is what an operator reading
	 *                 {@code service_lease} needs to see — a random uuid would tell
	 *                 them a lane has an owner and not which machine it is
	 */
	@Bean
	public LaneOwnership laneOwnership(LeaseManager leaseManager, ScopeSeam seam,
			@Value("${orca.edge.holder-id:${HOSTNAME:edge-local}}") String holderId,
			@Value("${orca.lease.duration}") Duration leaseDuration,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new LaneOwnership(leaseManager, seam, holderId, leaseDuration, siteExternalId);
	}

	/**
	 * The delivery port: a batched POST to runtime's {@code /internal/events/v1}.
	 *
	 * <p>ADR-011: no token is minted. The per-installation shared credential goes on
	 * the request, and {@code X-Orca-Service} is attribution only — a shared
	 * credential cannot prove which peer is calling, and treating it as authority
	 * would be reading more into it than it can carry.
	 */
	@Bean
	public DeliveryPump.EventDeliveryPort eventDeliveryPort(
			RestClient.Builder restClients,
			@Value("${orca.edge.runtime-base-url:http://localhost:8082}") String runtimeBaseUrl,
			@Value("${orca.internal.shared-credential}") String sharedCredential) {

		RestClient runtime = restClients
				.baseUrl(runtimeBaseUrl)
				.defaultHeader("X-Orca-Internal-Auth", sharedCredential)
				.defaultHeader("X-Orca-Service", "orca-edge")
				.defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
				.build();

		return (lane, batch) -> runtime.post()
				.uri("/internal/events/v1")
				.body(List.of(batch))
				.retrieve()
				.toBodilessEntity();
	}

	@Bean
	public DeliveryPump deliveryPump(EventBufferRepository buffer, LaneOwnership ownership,
			DeliveryPump.EventDeliveryPort delivery,
			@Value("${orca.edge.pump.batch-size:100}") int batchSize,
			@Value("${orca.edge.pump.max-attempts:10}") int maxAttempts) {
		return new DeliveryPump(buffer, ownership, delivery, batchSize, maxAttempts);
	}

	@Bean
	public IngestTasks ingestTasks(LaneOwnership ownership, DeliveryPump pump,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new IngestTasks(ownership, pump, siteExternalId);
	}

	/**
	 * The camera's framing — DERIVED-FROM-1X, see {@link LprFraming}.
	 *
	 * <p>A bean rather than a constant because it is the one thing here that a
	 * vendor specification or a capture from a fielded unit could still correct, and
	 * replacing it should be replacing one bean rather than unpicking assumptions
	 * from five classes.
	 */
	@Bean
	public LprFraming lprFraming() {
		return new LprFraming.ZapPacketStxEtx();
	}

	@Bean(destroyMethod = "close")
	public LprListener lprListener(LprFraming framing, EventBufferRepository buffer,
			LaneOwnership ownership, FencedWrite fencedWrite, ObjectMapper json,
			@Value("${orca.edge.lpr.port:9100}") int port,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new LprListener(port, siteExternalId, framing, buffer, ownership, fencedWrite, json);
	}

	/**
	 * Binds the listener after the context is ready, not during it.
	 *
	 * <p>A camera that connected mid-refresh would meet a service whose lease
	 * election has not run, be told no lane is owned, and go away — so binding is
	 * deferred until everything it depends on exists.
	 *
	 * <p>A failure to bind is deliberately fatal. An edge instance that came up
	 * healthy with no listener would answer {@code /actuator/health} 200 while every
	 * camera at the site silently failed to connect, which is the worst version of
	 * this failure: §C3 says edge is the service whose availability the lane
	 * depends on.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void bindListener(ApplicationReadyEvent ready) {
		LprListener listener = ready.getApplicationContext().getBean(LprListener.class);
		try {
			listener.start();
		}
		catch (java.io.IOException notBound) {
			throw new IllegalStateException(
					"The LPR listener could not bind. An edge instance without one reports healthy "
							+ "while every camera at the site fails to connect.", notBound);
		}
	}
}
