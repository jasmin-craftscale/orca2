package com.lynxis.orca.runtime.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.DeviceCommandPort;
import com.lynxis.orca.runtime.execution.domain.InboundDeviceEvent;
import com.lynxis.orca.runtime.integration.api.ConnectorPort;
import com.lynxis.orca.runtime.readmodel.domain.LaneMonitorService;
import com.lynxis.orca.runtime.readmodel.domain.LaneMonitorTables.LaneMonitorRow;
import com.lynxis.orca.runtime.workitem.RoutingTopologyFixture;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;

/**
 * Proves the first readmodel projection as a maintained copy, not a request-time
 * join.
 */
@SpringBootTest(
		classes = { RuntimeApplication.class, LaneMonitorProjectionIT.StubPorts.class },
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + LaneMonitorProjectionIT.SCHEMA,
				"spring.flyway.schemas=" + LaneMonitorProjectionIT.SCHEMA,
				"spring.flyway.default-schema=" + LaneMonitorProjectionIT.SCHEMA,
				"orca.required-views=",
				"flowable.async-executor-activate=true",
				"orca.installation.site-external-id=" + LaneMonitorProjectionIT.SITE,
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
		})
@org.springframework.test.annotation.DirtiesContext(
		classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LaneMonitorProjectionIT {

	static final String SCHEMA = "runtime";
	static final String SITE = "SITE-RM";
	static final String OTHER_SITE = "SITE-OTHER";
	static final String LANE = "LANE-RM-01";

	@Autowired
	private AdmissionService admission;

	@Autowired
	private WorkItemService workItems;

	@Autowired
	private LaneMonitorService laneMonitors;

	@Autowired
	private RuntimeService runtimeService;

	@Autowired
	private StubPorts.Recorder recorder;

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@DynamicPropertySource
	static void pointAtTheSchema(DynamicPropertyRegistry registry) {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);
		publishTopologyLane();
		admin("GRANT SELECT ON core.topology_lane TO [it_" + SCHEMA + "]");
		RoutingTopologyFixture.publish(LaneMonitorProjectionIT::admin, "it_" + SCHEMA);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		recorder.reset();
		runtimeService.createProcessInstanceQuery().list().forEach(instance ->
				runtimeService.deleteProcessInstance(instance.getId(), "test cleanup"));
		for (String table : List.of("work_item_audit", "work_item", "outbox_delivery", "outbox",
				"execution_event", "lane_monitor", "execution", "lane_session", "idempotency_record")) {
			jdbc.execute("DELETE FROM " + table);
		}
		for (String table : List.of("topology_screen", "topology_team_routing",
				"topology_team_member", "topology_operator", "topology_setting")) {
			admin("DELETE FROM core." + table);
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a truck appears on the lane monitor when admitted, then closes on the same row")
	void aTruckAppearsOnTheBoardAndThenClosesOnTheSameRow() {
		recorder.connectorBlocked.set(true);
		recorder.connectorOutcome.set("APPROVED");
		AdmissionService.EventOutcome admitted;
		try {
			admitted = admit("{\"plate\":\"RM-001\",\"gate_arm\":\"DOWN\",\"entry_loop\":true}");

			LaneMonitorRow active = boardRow();
			assertThat(active.trafficStatus()).isEqualTo("ACTIVE");
			assertThat(active.trafficColor()).isEqualTo("BLUE");
			assertThat(active.visitExternalId()).isEqualTo(admitted.visitExternalId());
			assertThat(active.plate()).isEqualTo("RM-001");
			assertThat(active.gateArm()).isEqualTo("DOWN");
			assertThat(active.loopInputs()).contains("entry_loop");
			assertThat(active.laneExternalId()).isEqualTo(LANE);
			assertThat(active.areaExternalId()).isEqualTo("AREA-RM");
		}
		finally {
			recorder.connectorBlocked.set(false);
		}

		awaitExecutionStatus(admitted.visitExternalId(), "COMPLETED");
		LaneMonitorRow completed = boardRow();
		assertThat(completed.visitExternalId()).isEqualTo(admitted.visitExternalId());
		assertThat(completed.trafficStatus()).isEqualTo("COMPLETED");
		assertThat(completed.trafficColor()).isEqualTo("GREEN");
		assertThat(completed.queuedWorkItemExternalId()).isNull();
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("manual work changes are projected in the same transactions as the work item")
	void manualWorkChangesAreProjected() {
		recorder.connectorUnavailable.set(true);
		String visit = admit("{\"plate\":\"RM-002\"}").visitExternalId();
		WorkItemRow item = queuedItemOf(visit);

		LaneMonitorRow queued = awaitBoard(row -> item.externalId().equals(row.queuedWorkItemExternalId()));
		assertThat(queued.trafficStatus()).isEqualTo("ACTIVE");
		assertThat(queued.queuedWorkItemQueuedAt()).isEqualTo(item.queuedAt());

		inScope(() -> workItems.take(item.externalId(), "op-a"));
		assertThat(boardRow().queuedWorkItemExternalId()).isNull();

		inScope(() -> workItems.park(item.externalId(), "op-a"));
		assertThat(boardRow().queuedWorkItemExternalId()).isEqualTo(item.externalId());

		inScope(() -> workItems.take(item.externalId(), "op-a"));
		inScope(() -> workItems.complete(item.externalId(), "op-a", "{\"decision\":\"fixed\"}"));
		LaneMonitorRow completed = boardRow();
		assertThat(completed.trafficStatus()).isEqualTo("MANUAL");
		assertThat(completed.trafficColor()).isEqualTo("AMBER");
		assertThat(completed.queuedWorkItemExternalId()).isNull();
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a projection write fault rolls admission back, so truth and board cannot split")
	void projectionFaultRollsAdmissionBack() {
		poisonTable("lane_monitor");
		try {
			assertThatThrownBy(() -> admit("{\"plate\":\"RM-003\"}"))
					.isInstanceOf(DataAccessException.class);
			assertThat(count("SELECT COUNT(*) FROM execution")).isZero();
			assertThat(count("SELECT COUNT(*) FROM execution_event")).isZero();
			assertThat(count("SELECT COUNT(*) FROM lane_monitor")).isZero();
		}
		finally {
			unpoisonTable("lane_monitor");
		}

		recorder.connectorBlocked.set(true);
		String visit;
		try {
			visit = admit("{\"plate\":\"RM-003\"}").visitExternalId();
			assertThat(boardRow().visitExternalId()).isEqualTo(visit);
		}
		finally {
			recorder.connectorBlocked.set(false);
		}
		awaitExecutionStatus(visit, "COMPLETED");
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a later completion fault rolls back the visit close and its projection update")
	void completionFaultRollsBackProjectionUpdate() {
		recorder.connectorUnavailable.set(true);
		String visit = admit("{\"plate\":\"RM-004\"}").visitExternalId();
		WorkItemRow item = queuedItemOf(visit);
		inScope(() -> workItems.take(item.externalId(), "op-a"));

		poisonTable("work_item_audit");
		try {
			assertThatThrownBy(() -> inScope(() ->
					workItems.complete(item.externalId(), "op-a", null)))
					.isInstanceOf(DataAccessException.class);
		}
		finally {
			unpoisonTable("work_item_audit");
		}

		assertThat(itemStatus(item.externalId())).isEqualTo("IN_PROGRESS");
		assertThat(executionStatus(visit)).isEqualTo("ACTIVE");
		assertThat(boardRow().trafficStatus()).isEqualTo("ACTIVE");

		inScope(() -> workItems.complete(item.externalId(), "op-a", null));
		assertThat(boardRow().trafficStatus()).isEqualTo("MANUAL");
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a board read under one site's scope never returns another site's lane")
	void boardReadsAreScopedByConstruction() {
		jdbc.update("INSERT INTO lane_monitor (site_external_id, lane_id, lane_external_id, "
						+ "traffic_status, traffic_color) VALUES (?, 1, ?, 'CLEAR', 'NEUTRAL')",
				SITE, LANE);
		jdbc.update("INSERT INTO lane_monitor (site_external_id, lane_id, lane_external_id, "
						+ "traffic_status, traffic_color) VALUES (?, 2, 'LANE-OTHER', 'CLEAR', 'NEUTRAL')",
				OTHER_SITE);

		assertThat(boardFor(SITE).stream().map(LaneMonitorRow::laneExternalId))
				.containsExactly(LANE);
		assertThat(boardFor(OTHER_SITE).stream().map(LaneMonitorRow::laneExternalId))
				.containsExactly("LANE-OTHER");
	}

	// ---------------------------------------------------------------------

	private AdmissionService.EventOutcome admit(String attributes) {
		return inScope(() -> admission.accept(new InboundDeviceEvent("evt-" + UUID.randomUUID(),
				LANE, "DEV-RM-CAMERA", "lpr.capture", attributes, Instant.now())));
	}

	private WorkItemRow queuedItemOf(String visitExternalId) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (System.nanoTime() < deadline) {
			List<WorkItemRow> rows = jdbc.query("SELECT external_id, task_id, status, queued_at "
							+ "FROM work_item WHERE visit_external_id = ?",
					(rs, n) -> new WorkItemRow(rs.getString("external_id"),
							rs.getString("task_id"), rs.getString("status"),
							com.lynxis.orca.runtime.persistence.Utc.instantAt(rs, "queued_at")),
					visitExternalId);
			if (!rows.isEmpty()) {
				return rows.getFirst();
			}
			sleep(100);
		}
		throw new AssertionError("no work item ever appeared for visit " + visitExternalId);
	}

	private LaneMonitorRow boardRow() {
		return awaitBoard(row -> true);
	}

	private LaneMonitorRow awaitBoard(java.util.function.Predicate<LaneMonitorRow> predicate) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (System.nanoTime() < deadline) {
			List<LaneMonitorRow> rows = boardFor(SITE);
			if (!rows.isEmpty() && predicate.test(rows.getFirst())) {
				return rows.getFirst();
			}
			sleep(100);
		}
		throw new AssertionError("no lane-monitor row matched before the timeout");
	}

	private List<LaneMonitorRow> boardFor(String siteExternalId) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(siteExternalId)),
				() -> laneMonitors.list(null, 50));
	}

	private void awaitExecutionStatus(String visitExternalId, String status) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (System.nanoTime() < deadline) {
			if (status.equals(executionStatus(visitExternalId))) {
				return;
			}
			sleep(100);
		}
		throw new AssertionError("visit " + visitExternalId + " never reached " + status);
	}

	private String executionStatus(String visitExternalId) {
		List<String> statuses = jdbc.queryForList("SELECT status FROM execution WHERE external_id = ?",
				String.class, visitExternalId);
		return statuses.isEmpty() ? null : statuses.getFirst();
	}

	private String itemStatus(String itemExternalId) {
		return jdbc.queryForObject("SELECT status FROM work_item WHERE external_id = ?",
				String.class, itemExternalId);
	}

	private long count(String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0L : counted;
	}

	private <T> T inScope(java.util.function.Supplier<T> action) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(SITE)), action::get);
	}

	private void poisonTable(String table) {
		admin("CREATE TRIGGER runtime.tr_poison_" + table + " ON runtime." + table
				+ " INSTEAD OF INSERT AS BEGIN THROW 50001, 'poisoned by LaneMonitorProjectionIT', 1; END");
	}

	private void unpoisonTable(String table) {
		admin("DROP TRIGGER runtime.tr_poison_" + table);
	}

	private static void publishTopologyLane() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT CAST(1 AS BIGINT) AS lane_id, "
				+ "''" + LANE + "'' AS lane_external_id, ''" + SITE + "'' AS site_external_id, "
				+ "''" + SITE + "'' AS site_code, CAST(1 AS BIT) AS site_is_primary, "
				+ "CAST(10 AS BIGINT) AS area_id, ''AREA-RM'' AS area_external_id, "
				+ "''AREA'' AS area_code, ''L01'' AS lane_code, N''Lane 1'' AS lane_name, "
				+ "CAST(1 AS INT) AS lane_priority, CAST(0 AS BIT) AS is_out_of_service')");
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

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	private record WorkItemRow(String externalId, String taskId, String status, Instant queuedAt) {
	}

	@Configuration(proxyBeanMethods = false)
	static class StubPorts {

		@Bean
		Recorder laneMonitorRecorder() {
			return new Recorder();
		}

		@Bean
		@Primary
		ConnectorPort connector(Recorder recorder) {
			return call -> {
				while (recorder.connectorBlocked.get()) {
					sleep(50);
				}
				if (recorder.connectorUnavailable.get()) {
					throw new ConnectorPort.ConnectorUnavailableException("down for lane monitor test");
				}
				return recorder.connectorOutcome.get();
			};
		}

		@Bean
		@Primary
		DeviceCommandPort deviceCommand() {
			return command -> DeviceCommandPort.EXECUTED;
		}

		static class Recorder {

			final AtomicReference<String> connectorOutcome = new AtomicReference<>("APPROVED");
			final AtomicBoolean connectorUnavailable = new AtomicBoolean();
			final AtomicBoolean connectorBlocked = new AtomicBoolean();

			void reset() {
				connectorOutcome.set("APPROVED");
				connectorUnavailable.set(false);
				connectorBlocked.set(false);
			}
		}
	}
}
