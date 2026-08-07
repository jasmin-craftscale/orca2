package com.lynxis.orca.edge;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import com.lynxis.orca.edge.api.DeviceCommandController;
import com.lynxis.orca.edge.domain.DeliveryPump;
import com.lynxis.orca.edge.domain.DeviceCommandService;
import com.lynxis.orca.edge.domain.DeviceHostPort;
import com.lynxis.orca.edge.domain.IngestTasks;
import com.lynxis.orca.edge.domain.LaneOwnership;
import com.lynxis.orca.edge.domain.LprFraming;
import com.lynxis.orca.edge.domain.LprListener;
import com.lynxis.orca.edge.domain.RestDeviceHost;
import com.lynxis.orca.edge.domain.RuntimeEventWire;
import com.lynxis.orca.edge.persistence.CommandLogRepository;
import com.lynxis.orca.edge.persistence.EventBufferRepository;
import com.lynxis.orca.platform.idempotency.IdempotencyStore;
import com.lynxis.orca.platform.lease.FencedWrite;
import com.lynxis.orca.platform.lease.LeaseManager;
import com.lynxis.orca.platform.scope.ScopeSeam;

import tools.jackson.databind.json.JsonMapper;

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
	 *
	 * <p>⚠️ Built with {@code RestClient.builder()} and not with an injected
	 * {@code RestClient.Builder}.
	 *
	 * <p>That is a fix, not a preference. Spring Boot 4 does not auto-configure a
	 * {@code RestClient.Builder} bean here, so the injected form made this service
	 * <strong>fail to start</strong> — and no test saw it, because every suite
	 * constructs these beans directly rather than refreshing the context. Found by
	 * running the demo; recorded in the phase report.
	 *
	 * <p>The deadline is new with it. §B8 requires every external call to have one,
	 * and the pump's POST had none: a runtime that accepted the connection and then
	 * went quiet would have held the pump's only thread indefinitely, which is a
	 * lane that stops draining rather than one that retries.
	 */
	@Bean
	public DeliveryPump.EventDeliveryPort eventDeliveryPort(
			@Value("${orca.edge.runtime-base-url:http://localhost:8082}") String runtimeBaseUrl,
			@Value("${orca.edge.pump.deadline:10s}") Duration deadline,
			@Value("${orca.internal.shared-credential}") String sharedCredential) {

		JdkClientHttpRequestFactory transport = new JdkClientHttpRequestFactory();
		transport.setReadTimeout(deadline);

		RestClient runtime = RestClient.builder()
				.requestFactory(transport)
				.baseUrl(runtimeBaseUrl)
				.defaultHeader("X-Orca-Internal-Auth", sharedCredential)
				.defaultHeader("X-Orca-Service", "orca-edge")
				.defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
				.build();

		return (lane, batch) -> runtime.post()
				.uri("/internal/events/v1")
				.body(RuntimeEventWire.Batch.of(batch))
				.retrieve()
				// Any non-2xx throws, and the pump treats every failure identically:
				// nothing dropped, nothing skipped, the batch stays buffered in order.
				// That includes the 422 runtime answers for a lane this installation
				// does not have — bounded by the pump's attempt limit, after which the
				// event is DEAD and visible rather than silently gone.
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
			LaneOwnership ownership, FencedWrite fencedWrite, JsonMapper json,
			@Value("${orca.edge.lpr.port:9100}") int port,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new LprListener(port, siteExternalId, framing, buffer, ownership, fencedWrite, json);
	}

	// --- WP7 · commands in (§C3) ----------------------------------------------

	@Bean
	public CommandLogRepository commandLogRepository(ScopeSeam seam) {
		return new CommandLogRepository(seam);
	}

	/** The outbound device-host shape is DERIVED-FROM-1X — see {@link RestDeviceHost}. */
	@Bean
	public DeviceHostPort deviceHostPort() {
		return new RestDeviceHost();
	}

	@Bean
	public DeviceCommandService deviceCommandService(CommandLogRepository commandLog,
			DeviceHostPort deviceHost, IdempotencyStore idempotency,
			@Value("${orca.installation.site-external-id}") String siteExternalId,
			@Value("${orca.edge.holder-id:${HOSTNAME:edge-local}}") String holderId) {
		return new DeviceCommandService(commandLog, deviceHost, idempotency, siteExternalId, holderId);
	}

	@Bean
	public DeviceCommandController deviceCommandController(DeviceCommandService commands,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new DeviceCommandController(commands, siteExternalId);
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
