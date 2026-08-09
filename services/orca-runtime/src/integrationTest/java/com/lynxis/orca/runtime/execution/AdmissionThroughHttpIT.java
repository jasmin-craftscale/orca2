package com.lynxis.orca.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.api.generated.model.DeviceEvent;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventBatch;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventBatchEnvelope;
import com.lynxis.orca.runtime.api.generated.model.DeviceEventResult;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;

/**
 * <strong>The thousand-truck admission test, through HTTP.</strong>
 *
 * <p>{@code AdmissionPropertiesIT} proves admission at the service call against real Flowable
 * and real SQL Server. What it could not prove is that the property survives the
 * <em>path</em>: a controller, a scope established from configuration, an
 * idempotency claim per event, a batch, and Spring's own transaction boundaries.
 * Everything between the socket and the lane lock is new, and every one of those
 * layers is somewhere a read-then-write can reappear.
 *
 * <p><strong>Both lane shapes run.</strong> The service-level suite spreads its thousand across
 * eight lanes, which is more contention but is <em>not</em> the shape a single-lane
 * site has. This suite covers both shapes:
 *
 * <ol>
 *   <li>{@link #eightLanesInParallelAdmitExactlyOneVisitPerTruck()} — 8 lanes ×
 *       125 trucks, two simultaneous events each.</li>
 *   <li>{@link #oneThousandConsecutiveTrucksThroughOneLane()} — 1,000 trucks
 *       through <em>one</em> lane, one after another, which is what the filtered
 *       unique index has to release and re-take a thousand times.</li>
 * </ol>
 */
@SpringBootTest(
		classes = RuntimeApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + AdmissionThroughHttpIT.SCHEMA,
				"spring.flyway.schemas=" + AdmissionThroughHttpIT.SCHEMA,
				"spring.flyway.default-schema=" + AdmissionThroughHttpIT.SCHEMA,
				// The gate is proven by RequiredViewsGateIT. Here it would only be in
				// the way: this suite publishes core.topology_lane itself and no
				// topology_device, because admission reads neither device nor host.
				"orca.required-views=",
				// Admission is one transaction and this suite is about what that
				// transaction guarantees. A background worker committing on its own
				// schedule would be noise in exactly the measurement being taken — and
				// gate-visit's service tasks are async, so nothing runs without one.
				"flowable.async-executor-activate=false",
				// Sixteen concurrent posts plus the server's own work. The committed
				// value is 10, which would turn the property under test into a
				// measurement of pool contention.
				"spring.datasource.hikari.maximum-pool-size=32",
				"orca.installation.site-external-id=" + AdmissionThroughHttpIT.SITE,
				// NOT the committed fixture: InternalCredentialValidator refuses that
				// outside the `local` profile, and it is right to. Long enough to pass
				// the 32-character floor, which is also not a `local` exemption here.
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
		})
class AdmissionThroughHttpIT {

	/** The production migrations stamp extended properties on a schema called `runtime` by name. */
	static final String SCHEMA = "runtime";

	static final String SITE = "SITE-IT";

	private static final Logger log = LoggerFactory.getLogger(AdmissionThroughHttpIT.class);

	/** A gate with eight lanes. Enough that lanes contend, few enough to be a real site. */
	private static final int LANES = 8;

	/** Repeats 1,000 times so a race that fails once in 200 cannot pass by luck. */
	private static final int TRUCKS = 1_000;

	private static final String EVENTS = "/internal/events/v1";

	@LocalServerPort
	private int port;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private AdmissionRepository repository;

	private RestClient edge;
	private JdbcTemplate jdbc;

	/**
	 * The schema, and core's published view, both before the context loads.
	 *
	 * <p>Ordering is why the view is published here rather than in a
	 * {@code @BeforeAll}: the integration database does not exist until
	 * {@code migratedSchema} creates it, and Spring runs {@code @BeforeAll} before it
	 * loads the context — so a {@code @BeforeAll} that reached for {@code core} would
	 * be connecting to a database nobody had made yet.
	 */
	@DynamicPropertySource
	static void pointAtTheSchema(DynamicPropertyRegistry registry) {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);

		// Standing in for orca-core having migrated. Admission resolves a lane
		// external id through this view and through nothing else — an id this site
		// does not publish resolves to empty, and it is the scope predicate that
		// makes that true rather than a check written in Java.
		publishTopologyLane();
		grantTopologyLaneTo("it_" + SCHEMA);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DELETE FROM execution_event");
		jdbc.execute("DELETE FROM execution");
		jdbc.execute("DELETE FROM lane_session");
		jdbc.execute("DELETE FROM idempotency_record");

		edge = RestClient.builder()
				.baseUrl("http://localhost:" + port)
				// Exactly what orca-edge's delivery pump sends. No token is
				// minted: the identity provider authenticates people, and putting it on
				// the gate path would make a truck's admission depend on it.
				.defaultHeader("X-Orca-Internal-Auth", "integration-test-credential-not-a-fixture")
				.defaultHeader("X-Orca-Service", "orca-edge")
				.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	// ------------------------------------------------------------------------
	// Shape 1 — eight lanes, two simultaneous events per truck.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 30, unit = TimeUnit.MINUTES)
	@DisplayName("1,000 trucks across 8 lanes, two simultaneous events each: exactly 1,000 visits")
	void eightLanesInParallelAdmitExactlyOneVisitPerTruck() throws Exception {
		int perLane = TRUCKS / LANES;
		List<Long> latenciesMicros = new java.util.concurrent.CopyOnWriteArrayList<>();

		try (ExecutorService lanes = Executors.newVirtualThreadPerTaskExecutor();
				ExecutorService pairs = Executors.newVirtualThreadPerTaskExecutor()) {

			List<Future<int[]>> perLaneResults = new ArrayList<>();
			for (int lane = 1; lane <= LANES; lane++) {
				String laneExternalId = laneOf(lane);
				perLaneResults.add(lanes.submit(() -> {
					int started = 0;
					int correlated = 0;
					for (int truck = 0; truck < perLane; truck++) {
						String plate = "T-%d-%04d".formatted(laneExternalId.hashCode() & 0xff, truck);
						// TWO DEVICE EVENTS FOR ONE TRUCK, released together. Distinct
						// uuids, because this is two cameras seeing the same truck — not
						// one delivery repeated, which is the other test.
						CyclicBarrier together = new CyclicBarrier(2);
						List<Callable<DeviceEventBatchEnvelope>> both = List.of(
								post(together, laneExternalId, plate, latenciesMicros),
								post(together, laneExternalId, plate, latenciesMicros));

						List<Future<DeviceEventBatchEnvelope>> answers = pairs.invokeAll(both);
						for (Future<DeviceEventBatchEnvelope> answer : answers) {
							DeviceEventResult result = onlyResult(answer.get());
							if (result.getStatus() == DeviceEventResult.StatusEnum.STARTED) {
								started++;
							}
							else {
								assertThat(result.getStatus())
										.as("the loser CORRELATES. It is not an error and it is not a "
												+ "second visit — collapsing the two into 'ok' is how a "
												+ "second visit gets started by a path that only meant "
												+ "to be tolerant")
										.isEqualTo(DeviceEventResult.StatusEnum.CORRELATED);
								correlated++;
							}
						}
						// The truck leaves. Shipping process completion does this; here
						// it is what frees the lane's filtered unique index for the next.
						completeVisitOn(laneExternalId);
					}
					return new int[] { started, correlated };
				}));
			}

			int started = 0;
			int correlated = 0;
			for (Future<int[]> perLaneResult : perLaneResults) {
				int[] counted = perLaneResult.get();
				started += counted[0];
				correlated += counted[1];
			}

			assertThat(started).as("one visit per truck, through HTTP").isEqualTo(TRUCKS);
			assertThat(correlated).as("and the other event of each pair joined it").isEqualTo(TRUCKS);
		}

		assertThat(countVisits()).as("exactly 1,000 visit rows, no doubles").isEqualTo(TRUCKS);
		assertThat(countEvents()).as("and every one of the 2,000 events is attached to one of them")
				.isEqualTo(2L * TRUCKS);
		recordLatency("8 lanes x 125 trucks, 2 simultaneous events", latenciesMicros);
	}

	// ------------------------------------------------------------------------
	// Shape 2 — one lane, a thousand trucks in a row. The single-lane site.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 30, unit = TimeUnit.MINUTES)
	@DisplayName("1,000 consecutive trucks through ONE lane: 1,000 visits, one at a time")
	void oneThousandConsecutiveTrucksThroughOneLane() {
		// The previously uncovered single-lane shape. It is not a weaker version of
		// the test above: eight lanes never make one lane's filtered unique index
		// release and re-take a thousand times, and that release is what lets the NEXT
		// truck in. A predicate that included completed visits would pass the
		// eight-lane test and deadlock a single-lane site on its second truck.
		String lane = laneOf(1);
		List<Long> latenciesMicros = new ArrayList<>();

		for (int truck = 0; truck < TRUCKS; truck++) {
			DeviceEventResult result = onlyResult(
					deliver(lane, "T-SINGLE-%04d".formatted(truck), latenciesMicros));

			assertThat(result.getStatus())
					.as("truck %d must START a visit. A CORRELATED here means the previous "
							+ "truck's visit never released the lane", truck)
					.isEqualTo(DeviceEventResult.StatusEnum.STARTED);

			completeVisitOn(lane);
		}

		assertThat(countVisits()).isEqualTo(TRUCKS);
		assertThat(activeVisitsOn(lane))
				.as("the lane is free at the end, not holding the thousandth truck forever")
				.isZero();
		recordLatency("1 lane x 1,000 consecutive trucks", latenciesMicros);
	}

	// ------------------------------------------------------------------------
	// Redelivery: the sender did not receive the first acknowledgement.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("the same batch delivered twice has one effect, and the second is told what the first produced")
	void aRedeliveredBatchHasOneEffect() {
		String lane = laneOf(2);
		DeviceEventBatch batch = batchOf(lane, "T-REDELIVERED", UUID.randomUUID().toString());

		DeviceEventResult first = onlyResult(send(batch));
		assertThat(first.getStatus()).isEqualTo(DeviceEventResult.StatusEnum.STARTED);

		// Edge resends a batch it never saw acknowledged. This boundary takes that
		// trade knowingly: a lost event is unrecoverable, a repeated one is not.
		DeviceEventResult second = onlyResult(send(batch));

		assertThat(second.getStatus())
				.as("NOT an error. The caller retried BECAUSE it never saw the first answer, so a "
						+ "rejection is the one response it cannot use")
				.isEqualTo(DeviceEventResult.StatusEnum.DUPLICATE);
		assertThat(second.getVisitExternalId())
				.as("and it is told the same visit, byte for byte")
				.isEqualTo(first.getVisitExternalId());

		assertThat(countVisits()).isEqualTo(1);
		assertThat(countEvents()).as("one effect, not two").isEqualTo(1);
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("two CONCURRENT deliveries of the same batch still have one effect")
	void twoConcurrentDeliveriesOfOneBatchHaveOneEffect() throws Exception {
		String lane = laneOf(3);
		DeviceEventBatch batch = batchOf(lane, "T-CONCURRENT", UUID.randomUUID().toString());

		// The sequential case above is the easy one. This is the one the idempotency
		// store exists for: two deliveries in flight at once, neither having seen the
		// other, both claiming the same key.
		CyclicBarrier together = new CyclicBarrier(2);
		try (ExecutorService both = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<DeviceEventBatchEnvelope>> answers = both.invokeAll(List.of(
					sendTogether(together, batch), sendTogether(together, batch)));

			List<DeviceEventResult.StatusEnum> statuses = new ArrayList<>();
			for (Future<DeviceEventBatchEnvelope> answer : answers) {
				statuses.add(onlyResult(answer.get()).getStatus());
			}
			assertThat(statuses)
					.as("exactly one admitted it; the other learned that, and neither was refused")
					.containsExactlyInAnyOrder(DeviceEventResult.StatusEnum.STARTED,
							DeviceEventResult.StatusEnum.DUPLICATE);
		}

		assertThat(countVisits()).isEqualTo(1);
		assertThat(countEvents()).isEqualTo(1);
	}

	// ------------------------------------------------------------------------
	// The lane this installation does not have.
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("an event for a lane this site does not publish is refused, and refuses the whole batch")
	void anEventForAnUnknownLaneIsRefused() {
		DeviceEventBatch batch = new DeviceEventBatch().events(List.of(
				event(laneOf(4), "T-FINE", UUID.randomUUID().toString()),
				event("LANE-AT-ANOTHER-SITE", "T-NOT-OURS", UUID.randomUUID().toString())));

		HttpStatusCode status = edge.post().uri(EVENTS).body(batch)
				.exchange((request, response) -> response.getStatusCode());

		assertThat(status.value())
				.as("422, not 404: the request is well formed and the route is right. What is wrong "
						+ "is that this installation cannot act on it")
				.isEqualTo(422);
		assertThat(countVisits())
				.as("the WHOLE batch is refused. Edge acknowledges a batch or none of it, so a "
						+ "partial success would be acknowledged as a whole one and the rest lost")
				.isZero();
	}

	// ------------------------------------------------------------------------

	private Callable<DeviceEventBatchEnvelope> post(CyclicBarrier together, String lane, String plate,
			List<Long> latenciesMicros) {
		return () -> {
			together.await(2, TimeUnit.MINUTES);
			return deliver(lane, plate, latenciesMicros);
		};
	}

	private Callable<DeviceEventBatchEnvelope> sendTogether(CyclicBarrier together, DeviceEventBatch batch) {
		return () -> {
			together.await(2, TimeUnit.MINUTES);
			return send(batch);
		};
	}

	private DeviceEventBatchEnvelope deliver(String lane, String plate, List<Long> latenciesMicros) {
		DeviceEventBatch batch = batchOf(lane, plate, UUID.randomUUID().toString());
		long start = System.nanoTime();
		DeviceEventBatchEnvelope answer = send(batch);
		latenciesMicros.add((System.nanoTime() - start) / 1_000);
		return answer;
	}

	private DeviceEventBatchEnvelope send(DeviceEventBatch batch) {
		return edge.post().uri(EVENTS).body(batch).retrieve().body(DeviceEventBatchEnvelope.class);
	}

	private static DeviceEventBatch batchOf(String lane, String plate, String eventUuid) {
		return new DeviceEventBatch().events(List.of(event(lane, plate, eventUuid)));
	}

	private static DeviceEvent event(String lane, String plate, String eventUuid) {
		return new DeviceEvent()
				.eventUuid(eventUuid)
				.laneExternalId(lane)
				.deviceExternalId("DEV-IT-CAMERA")
				.eventType("lpr.capture")
				// Exactly the normalised shape edge produces — no ZapPacket crosses this
				// link, which is what makes edge the hardware boundary.
				.attributes("{\"plate\":\"" + plate + "\",\"confidence\":\"0.94\"}");
	}

	private static DeviceEventResult onlyResult(DeviceEventBatchEnvelope envelope) {
		assertThat(envelope).isNotNull();
		assertThat(envelope.getData()).isNotNull();
		assertThat(envelope.getData().getResults()).hasSize(1);
		return envelope.getData().getResults().getFirst();
	}

	private static String laneOf(int lane) {
		return "LANE-IT-%02d".formatted(lane);
	}

	/** Frees the lane the way shipping process completion does. */
	private void completeVisitOn(String laneExternalId) {
		ScopeContext.runIn(Scope.of("site_external_id", Set.of(SITE)), () -> {
			long laneId = repository.laneIdOf(laneExternalId).orElseThrow();
			repository.activeRootOn(laneId).ifPresent(visit ->
					repository.completeVisit(visit.executionId(), "COMPLETED"));
		});
	}

	private long countVisits() {
		return count("SELECT COUNT(*) FROM execution");
	}

	private long countEvents() {
		return count("SELECT COUNT(*) FROM execution_event");
	}

	private long activeVisitsOn(String laneExternalId) {
		Long laneId = jdbc.queryForObject(
				"SELECT lane_id FROM core.topology_lane WHERE lane_external_id = ?", Long.class,
				laneExternalId);
		return count("SELECT COUNT(*) FROM execution WHERE lane_id = " + laneId + " AND status = 'ACTIVE'");
	}

	private long count(String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0 : counted;
	}

	/**
	 * Records HTTP admission latency without imposing a threshold.
	 *
	 * <p>⚠️ An upper bound and not a site measurement: the SQL Server image is
	 * amd64-only, so on Apple silicon the engine runs emulated on a two-CPU Docker
	 * VM. This is end-to-end over HTTP, which is what a site's edge instance
	 * actually waits for; {@code AdmissionPropertiesIT} measures the service call alone.
	 */
	private static void recordLatency(String shape, List<Long> micros) {
		long[] sorted = micros.stream().mapToLong(Long::longValue).sorted().toArray();
		if (sorted.length == 0) {
			return;
		}
		log.info("""
						WP6 · admission through HTTP — {} ({} deliveries)
						  min {} ms · p50 {} ms · p95 {} ms · p99 {} ms · max {} ms · mean {} ms""",
				shape, sorted.length,
				millis(sorted[0]), millis(percentile(sorted, 50)), millis(percentile(sorted, 95)),
				millis(percentile(sorted, 99)), millis(sorted[sorted.length - 1]),
				millis((long) Arrays.stream(sorted).average().orElse(0)));
	}

	private static long percentile(long[] sorted, int percentile) {
		return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile / 100.0) - 1)];
	}

	private static String millis(long micros) {
		return "%.1f".formatted(micros / 1000.0);
	}

	// ------------------------------------------------------------------------

	/**
	 * Core's published view, standing in for orca-core having migrated.
	 *
	 * <p>It publishes both the surrogate key and the external id, exactly as
	 * {@code V102__topology_views.sql} does — admission locks and indexes on
	 * {@code lane_id}, while everything crossing a service boundary uses the
	 * external identifier.
	 */
	private static void publishTopologyLane() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");

		StringBuilder rows = new StringBuilder();
		for (int lane = 1; lane <= LANES; lane++) {
			rows.append(lane == 1 ? "" : ", ")
					.append("(CAST(").append(lane).append(" AS BIGINT), ''")
					.append(laneOf(lane)).append("'', ''").append(SITE).append("'')");
		}
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT lane_id, lane_external_id, "
				+ "site_external_id FROM (VALUES " + rows + ") AS lanes (lane_id, lane_external_id, "
				+ "site_external_id)')");
	}

	private static void grantTopologyLaneTo(String login) {
		admin("GRANT SELECT ON core.topology_lane TO [" + login + "]");
	}

	private static void admin(String sql) {
		try (Connection connection = PlatformDatabase.administrative().getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (SQLException e) {
			throw new IllegalStateException("Failed: " + sql, e);
		}
	}
}
