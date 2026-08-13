package com.lynxis.orca.runtime.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.execution.api.PartnerEventAdmissionPort;
import com.lynxis.orca.runtime.integration.domain.PartnerEventAdmission;

/** Property proofs for A1's partner-to-execution admission seam. */
@SpringBootTest(
		classes = RuntimeApplication.class,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + PartnerEventAdmissionPropertiesIT.SCHEMA,
				"spring.flyway.schemas=" + PartnerEventAdmissionPropertiesIT.SCHEMA,
				"spring.flyway.default-schema=" + PartnerEventAdmissionPropertiesIT.SCHEMA,
				"orca.required-views=",
				"flowable.async-executor-activate=false",
				"spring.datasource.hikari.maximum-pool-size=12",
				"orca.installation.site-external-id=" + PartnerEventAdmissionPropertiesIT.SITE,
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
		})
class PartnerEventAdmissionPropertiesIT {

	static final String SCHEMA = "runtime";
	static final String SITE = "SITE-PARTNER-IT";

	private static final String LANE = "LANE-PARTNER-IT";
	private static final String PARTNER_OPERATION = "partner-event";
	private static final int ITERATIONS = 200;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PartnerEventAdmission admission;

	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void pointAtTheSchema(DynamicPropertyRegistry registry) {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);

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
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.MINUTES)
	@DisplayName("200 partner trucks, two concurrent submits each: exactly 200 visits")
	void twoConcurrentPartnerSubmitsForOneLaneProduceExactlyOneVisitForTwoHundredIterations()
			throws Exception {
		int started = 0;
		int correlated = 0;

		try (ExecutorService submissions = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int iteration = 0; iteration < ITERATIONS; iteration++) {
				String plate = "T-PARTNER-%03d".formatted(iteration);
				CyclicBarrier together = new CyclicBarrier(2);
				List<Callable<PartnerEventAdmissionPort.Outcome>> pair = List.of(
						submitTogether(together, event(plate, UUID.randomUUID().toString())),
						submitTogether(together, event(plate, UUID.randomUUID().toString())));

				List<Future<PartnerEventAdmissionPort.Outcome>> answers = submissions.invokeAll(pair);
				List<PartnerEventAdmissionPort.Outcome> outcomes = List.of(
						answers.get(0).get(), answers.get(1).get());

				assertThat(outcomes).extracting(PartnerEventAdmissionPort.Outcome::status)
						.as("iteration %d preserves the winner and the correlated submit", iteration)
						.containsExactlyInAnyOrder(PartnerEventAdmissionPort.Status.STARTED,
								PartnerEventAdmissionPort.Status.CORRELATED);
				assertThat(outcomes).extracting(PartnerEventAdmissionPort.Outcome::visitExternalId)
						.as("both submits name the one visit created in iteration %d", iteration)
						.doesNotContainNull()
						.containsOnly(outcomes.getFirst().visitExternalId());

				started++;
				correlated++;
				completeActiveVisit();
			}
		}

		assertThat(started).isEqualTo(ITERATIONS);
		assertThat(correlated).isEqualTo(ITERATIONS);
		assertThat(count("SELECT COUNT(*) FROM execution")).isEqualTo(ITERATIONS);
		assertThat(count("SELECT COUNT(*) FROM execution_event")).isEqualTo(2L * ITERATIONS);
		assertThat(count("SELECT COUNT(*) FROM idempotency_record WHERE operation = 'partner-event'"))
				.as("partner submissions have their own operation namespace")
				.isEqualTo(2L * ITERATIONS);
	}

	@Test
	@DisplayName("a partner replay returns DUPLICATE with the original visit")
	void replayPreservesDuplicateAndItsRecordedOutcome() {
		PartnerEventAdmissionPort.Event event = event("T-PARTNER-REPLAY", UUID.randomUUID().toString());

		PartnerEventAdmissionPort.Outcome first = admission.admit(event);
		PartnerEventAdmissionPort.Outcome replay = admission.admit(event);

		assertThat(first.status()).isEqualTo(PartnerEventAdmissionPort.Status.STARTED);
		assertThat(replay.status()).isEqualTo(PartnerEventAdmissionPort.Status.DUPLICATE);
		assertThat(replay.visitExternalId()).isEqualTo(first.visitExternalId());
		assertThat(count("SELECT COUNT(*) FROM execution")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM execution_event")).isEqualTo(1);
	}

	@Test
	@DisplayName("a camera idempotency record cannot answer for a partner event with the same key")
	void partnerAndDeviceEventsUseDifferentIdempotencyNamespaces() {
		String eventUuid = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO idempotency_record
					(idempotency_key, operation, status, outcome, holder_id, completed_at)
				VALUES (?, 'device-event', 'COMPLETED', 'vis-from-camera', NULL, SYSUTCDATETIME())
				""", eventUuid);

		PartnerEventAdmissionPort.Outcome outcome =
				admission.admit(event("T-PARTNER-NAMESPACE", eventUuid));

		assertThat(outcome.status()).isEqualTo(PartnerEventAdmissionPort.Status.STARTED);
		assertThat(outcome.visitExternalId()).isNotEqualTo("vis-from-camera");
		assertThat(count("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = '"
				+ eventUuid + "' AND operation IN ('device-event', 'partner-event')"))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("a partner event owned by another caller remains IN_PROGRESS and has no effect")
	void inProgressIsPreservedAsANonTerminalOutcome() {
		String eventUuid = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO idempotency_record
					(idempotency_key, operation, status, outcome, holder_id, completed_at)
				VALUES (?, ?, 'IN_PROGRESS', NULL, 'another-runtime', NULL)
				""", eventUuid, PARTNER_OPERATION);

		PartnerEventAdmissionPort.Outcome outcome =
				admission.admit(event("T-PARTNER-WAIT", eventUuid));

		assertThat(outcome.status()).isEqualTo(PartnerEventAdmissionPort.Status.IN_PROGRESS);
		assertThat(outcome.visitExternalId()).isNull();
		assertThat(count("SELECT COUNT(*) FROM execution")).isZero();
		assertThat(count("SELECT COUNT(*) FROM execution_event")).isZero();
	}

	private Callable<PartnerEventAdmissionPort.Outcome> submitTogether(CyclicBarrier together,
			PartnerEventAdmissionPort.Event event) {
		return () -> {
			together.await(2, TimeUnit.MINUTES);
			return admission.admit(event);
		};
	}

	private static PartnerEventAdmissionPort.Event event(String plate, String eventUuid) {
		return new PartnerEventAdmissionPort.Event(eventUuid, LANE, "partner.arrival",
				"{\"plate\":\"" + plate + "\"}", Instant.now());
	}

	private void completeActiveVisit() {
		int completed = jdbc.update("""
				UPDATE execution
				SET status = 'COMPLETED', completed_at = SYSUTCDATETIME()
				WHERE site_external_id = ? AND lane_id = 1 AND status = 'ACTIVE'
				""", SITE);
		assertThat(completed).as("the iteration created one active visit to release").isEqualTo(1);
	}

	private long count(String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0 : counted;
	}

	private static void publishTopologyLane() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT lane_id, lane_external_id, "
				+ "site_external_id, site_code, site_is_primary, area_id, area_external_id, area_code, "
				+ "lane_code, lane_name, lane_priority, is_out_of_service FROM (VALUES (CAST(1 AS BIGINT), ''"
				+ LANE + "'', ''" + SITE + "'', ''" + SITE
				+ "'', CAST(1 AS BIT), CAST(10 AS BIGINT), ''AREA-PARTNER-IT'', ''AREA'', "
				+ "''LP'', N''Partner Lane'', CAST(1 AS INT), CAST(0 AS BIT))) AS lanes "
				+ "(lane_id, lane_external_id, site_external_id, site_code, site_is_primary, "
				+ "area_id, area_external_id, area_code, lane_code, lane_name, lane_priority, "
				+ "is_out_of_service)')");
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
