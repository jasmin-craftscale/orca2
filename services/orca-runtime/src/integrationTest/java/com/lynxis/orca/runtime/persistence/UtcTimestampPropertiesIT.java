package com.lynxis.orca.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.execution.persistence.VisitReadRepository;
import com.lynxis.orca.runtime.workitem.persistence.WorkItemRepository;

@SpringBootTest(
		classes = RuntimeApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + UtcTimestampPropertiesIT.SCHEMA,
				"spring.flyway.schemas=" + UtcTimestampPropertiesIT.SCHEMA,
				"spring.flyway.default-schema=" + UtcTimestampPropertiesIT.SCHEMA,
				"orca.required-views=",
				"flowable.async-executor-activate=false",
				"orca.installation.site-external-id=" + UtcTimestampPropertiesIT.SITE,
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
		})
class UtcTimestampPropertiesIT {

	static final String SCHEMA = "runtime";
	static final String SITE = "SITE-UTC-IT";
	private static final ZoneId NON_UTC_ZONE = ZoneId.of("Australia/Sydney");

	private static TimeZone originalZone;

	@Autowired
	private WorkItemRepository workItems;

	@Autowired
	private VisitReadRepository visits;

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@BeforeAll
	static void forceNonUtcZone() {
		originalZone = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone(NON_UTC_ZONE));
	}

	@AfterAll
	static void restoreZone() {
		TimeZone.setDefault(originalZone);
	}

	@DynamicPropertySource
	static void pointAtTheSchema(DynamicPropertyRegistry registry) {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		for (String table : List.of("work_item_audit", "work_item", "execution_event", "execution",
				"lane_session", "idempotency_record")) {
			jdbc.execute("DELETE FROM " + table);
		}
	}

	@Test
	@DisplayName("a database-written timestamp reads as the instant the database meant")
	void databaseWrittenValueReadsAsUtc() {
		assertThat(ZoneId.systemDefault()).isEqualTo(NON_UTC_ZONE);
		Instant before = Instant.now();
		ItemFixture item = insertQueuedItem();
		Instant after = Instant.now();

		Instant queuedAt = inScope(() -> workItems.byExternalId(item.itemExternalId()).orElseThrow())
				.queuedAt();

		assertThat(queuedAt)
				.as("SYSUTCDATETIME must be read as a UTC wall clock, not the JVM's local wall clock")
				.isBetween(before.minusSeconds(5), after.plusSeconds(5));
	}

	@Test
	@DisplayName("a Java-written timestamp reads back as the instant Java meant")
	void javaWrittenValueRoundTripsToTheMillisecond() {
		assertThat(ZoneId.systemDefault()).isEqualTo(NON_UTC_ZONE);
		ItemFixture item = insertQueuedItem();
		Instant intended = Instant.parse("2026-01-02T03:04:05.678Z");

		assertThat(inScope(() -> workItems.take(item.itemExternalId(), "op-utc", intended))).isTrue();

		Instant readBack = inScope(() -> workItems.byExternalId(item.itemExternalId()).orElseThrow())
				.startedAt();
		assertThat(readBack).isEqualTo(intended);
	}

	@Test
	@DisplayName("a duration spanning a database-written and Java-written timestamp is real")
	void databaseToJavaWrittenDurationIsSecondsNotTheMachineOffset() {
		assertThat(ZoneId.systemDefault()).isEqualTo(NON_UTC_ZONE);
		ItemFixture item = insertQueuedItem();

		assertThat(inScope(() -> workItems.take(item.itemExternalId(), "op-utc", Instant.now()))).isTrue();
		assertThat(inScope(() -> workItems.complete(item.itemExternalId(), "op-utc", Instant.now(), 0, null)))
				.isTrue();

		long durationMillis = jdbc.queryForObject(
				"SELECT DATEDIFF_BIG(millisecond, queued_at, completed_at) "
						+ "FROM work_item WHERE external_id = ?",
				Long.class, item.itemExternalId());

		assertThat(durationMillis)
				.as("a work item completed immediately must not inherit the JVM's UTC offset")
				.isBetween(0L, 10_000L);
	}

	@Test
	@DisplayName("a visit search window is not shifted by the machine's zone")
	void visitSearchWindowIsHonest() {
		assertThat(ZoneId.systemDefault()).isEqualTo(NON_UTC_ZONE);
		String visitExternalId = "vis-window-" + UUID.randomUUID();
		String plate = "UTC-WINDOW-" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, plate, "
				+ "started_at) VALUES (?, ?, 1, 'ACTIVE', ?, DATEADD(minute, -1, SYSUTCDATETIME()))",
				visitExternalId, SITE, plate);

		List<VisitReadRepository.VisitRow> inside = inScope(() ->
				visits.search(null, null, plate, Instant.now().minusSeconds(5 * 60), 100));
		List<VisitReadRepository.VisitRow> outside = inScope(() ->
				visits.search(null, null, plate, Instant.now().plusSeconds(5 * 60), 100));

		assertThat(inside).extracting(VisitReadRepository.VisitRow::externalId)
				.containsExactly(visitExternalId);
		assertThat(outside).isEmpty();
	}

	private ItemFixture insertQueuedItem() {
		String visitExternalId = "vis-utc-" + UUID.randomUUID();
		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status) "
				+ "VALUES (?, ?, 1, 'ACTIVE')", visitExternalId, SITE);
		long executionId = jdbc.queryForObject(
				"SELECT execution_id FROM execution WHERE external_id = ?", Long.class, visitExternalId);

		String itemExternalId = "wi-utc-" + UUID.randomUUID();
		inScope(() -> workItems.insert(itemExternalId, SITE, executionId, 1, visitExternalId,
				"LANE-UTC-IT", "pi-utc", "task-" + UUID.randomUUID(), "utc-test", "manualInput",
				null, null));
		return new ItemFixture(itemExternalId);
	}

	private <T> T inScope(java.util.function.Supplier<T> action) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(SITE)), action::get);
	}

	private record ItemFixture(String itemExternalId) {
	}
}
