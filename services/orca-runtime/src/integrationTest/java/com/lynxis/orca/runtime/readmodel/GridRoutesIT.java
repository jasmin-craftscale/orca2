package com.lynxis.orca.runtime.readmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

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
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.api.generated.model.CompletedWorkGridRequest;
import com.lynxis.orca.runtime.api.generated.model.GridExportRequest;
import com.lynxis.orca.runtime.api.generated.model.GridName;
import com.lynxis.orca.runtime.api.generated.model.LaneAlert;
import com.lynxis.orca.runtime.api.generated.model.LaneAlertReason;
import com.lynxis.orca.runtime.api.generated.model.LaneAlertsGridRequest;
import com.lynxis.orca.runtime.api.generated.model.WorkItem;
import com.lynxis.orca.runtime.api.generated.model.WorkItemGridRequest;
import com.lynxis.orca.runtime.readmodel.api.GridController;
import com.lynxis.orca.runtime.workitem.RoutingTopologyFixture;

@SpringBootTest(
		classes = RuntimeApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + GridRoutesIT.SCHEMA,
				"spring.flyway.schemas=" + GridRoutesIT.SCHEMA,
				"spring.flyway.default-schema=" + GridRoutesIT.SCHEMA,
				"orca.required-views=",
				"flowable.async-executor-activate=false",
				"orca.installation.site-external-id=" + GridRoutesIT.SITE,
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
		})
@org.springframework.test.annotation.DirtiesContext(
		classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GridRoutesIT {

	static final String SCHEMA = "runtime";
	static final String SITE = "SITE-GRID";

	@Autowired
	private GridController grids;

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
		RoutingTopologyFixture.publish(GridRoutesIT::admin, "it_" + SCHEMA);
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		for (String table : List.of("grid_export_job", "lane_monitor", "work_item_audit",
				"work_item", "execution_event", "execution", "lane_session")) {
			jdbc.execute("DELETE FROM " + table);
		}
		for (String table : List.of("topology_screen", "topology_team_routing",
				"topology_team_member", "topology_operator", "topology_setting")) {
			admin("DELETE FROM core." + table);
		}
	}

	@Test
	@DisplayName("queue grid reuses workitem's routing-priority ordering")
	void queueGridUsesWorkItemPriorityOrdering() {
		insertRouting("screen-low", "LANE-GRID-01", 20);
		insertRouting("screen-high", "LANE-GRID-02", 5);
		Instant base = Instant.now().minusSeconds(600);

		insertOpenWorkItem("wi-unrouted", 3, "LANE-GRID-03", null, base.minusSeconds(60));
		insertOpenWorkItem("wi-low", 1, "LANE-GRID-01", "screen-low", base);
		insertOpenWorkItem("wi-high", 2, "LANE-GRID-02", "screen-high", base.plusSeconds(120));

		List<WorkItem> rows = grids.queueGrid(new WorkItemGridRequest().limit(10))
				.getBody().getData();

		assertThat(rows).extracting(WorkItem::getExternalId)
				.containsExactly("wi-high", "wi-low", "wi-unrouted");
	}

	@Test
	@DisplayName("completed-work grid is bounded by the completion window and newest-first")
	void completedWorkGridUsesWindowAndNewestFirstOrdering() {
		Instant now = Instant.now();
		insertTerminalWorkItem("wi-old", 1, "LANE-GRID-01", now.minusSeconds(172_800));
		insertTerminalWorkItem("wi-mid", 2, "LANE-GRID-02", now.minusSeconds(3_600));
		insertTerminalWorkItem("wi-new", 3, "LANE-GRID-03", now.minusSeconds(300));

		CompletedWorkGridRequest request = new CompletedWorkGridRequest()
				.status(CompletedWorkGridRequest.StatusEnum.fromValue("COMPLETED"))
				.completedFrom(offset(now.minusSeconds(86_400)))
				.limit(10);

		List<WorkItem> rows = grids.completedWorkGrid(request).getBody().getData();

		assertThat(rows).extracting(WorkItem::getExternalId)
				.containsExactly("wi-new", "wi-mid");
	}

	@Test
	@DisplayName("alerts grid is one projection route ordered by lane priority")
	void alertsGridUsesOneProjectionRouteOrderedByLanePriority() {
		insertLaneMonitor(4, "LANE-CLEAR", 4, "CLEAR", "NEUTRAL", null, null, false);
		insertLaneMonitor(3, "LANE-MANUAL", 3, "MANUAL", "AMBER", "visit-manual", null, false);
		insertLaneMonitor(1, "LANE-FAILED", 1, "FAILED", "RED", "visit-failed", null, false);
		insertLaneMonitor(2, "LANE-SLA", 2, "ACTIVE", "BLUE", "visit-sla",
				Instant.now().minusSeconds(60), false);
		insertLaneMonitor(0, "LANE-OOS", 0, "CLEAR", "NEUTRAL", null, null, true);

		List<LaneAlert> rows = grids.alertsGrid(new LaneAlertsGridRequest()
				.includeOutOfService(false)
				.limit(10)).getBody().getData();

		assertThat(rows).extracting(LaneAlert::getLaneExternalId)
				.containsExactly("LANE-FAILED", "LANE-SLA", "LANE-MANUAL");
		assertThat(reasons(rows.get(0))).containsExactly("FAILED");
		assertThat(reasons(rows.get(1))).containsExactly("SLA_BREACH");

		List<LaneAlert> withOutOfService = grids.alertsGrid(new LaneAlertsGridRequest()
				.includeOutOfService(true)
				.limit(10)).getBody().getData();
		assertThat(withOutOfService).extracting(LaneAlert::getLaneExternalId)
				.containsExactly("LANE-OOS", "LANE-FAILED", "LANE-SLA", "LANE-MANUAL");
	}

	@Test
	@DisplayName("grid export persists a scoped completed CSV job")
	void gridExportPersistsCompletedCsvJob() {
		insertLaneMonitor(1, "LANE-FAILED", 1, "FAILED", "RED", "visit-failed", null, false);
		insertLaneMonitor(2, "LANE-SLA", 2, "ACTIVE", "BLUE", "visit-sla",
				Instant.now().minusSeconds(60), false);

		var started = grids.startGridExport(new GridExportRequest()
				.grid(GridName.fromValue("ALERTS"))
				.limit(10)).getBody().getData();

		assertThat(started.getStatus().getValue()).isEqualTo("COMPLETED");
		assertThat(started.getRowCount()).isEqualTo(2);
		assertThat(started.getBody()).startsWith("laneExternalId,laneCode,laneName,lanePriority");
		assertThat(started.getBody()).contains("LANE-FAILED", "FAILED", "LANE-SLA", "SLA_BREACH");

		var fetched = grids.getGridExport(started.getExportExternalId()).getBody().getData();
		assertThat(fetched.getBody()).isEqualTo(started.getBody());
		assertThat(fetched.getFileName()).endsWith(".csv");
	}

	private void insertRouting(String screenExternalId, String laneExternalId, int priority) {
		admin("INSERT INTO core.topology_team_routing (site_external_id, team_external_id, "
				+ "team_name, handling_method, screen_external_id, process_definition_key, "
				+ "node_reference, lane_external_id, priority) VALUES ('" + SITE
				+ "', 'team-grid', N'Team grid', 'PROMPT', '" + screenExternalId
				+ "', 'proc-grid', 'node-grid', '" + laneExternalId + "', " + priority + ")");
	}

	private void insertOpenWorkItem(String externalId, long laneId, String laneExternalId,
			String screenExternalId, Instant queuedAt) {
		long executionId = insertExecution("visit-" + externalId, laneId, "ACTIVE");
		insertWorkItem(externalId, executionId, laneId, laneExternalId, screenExternalId, "QUEUED",
				null, queuedAt, null);
	}

	private void insertTerminalWorkItem(String externalId, long laneId, String laneExternalId,
			Instant completedAt) {
		long executionId = insertExecution("visit-" + externalId, laneId, "COMPLETED");
		insertWorkItem(externalId, executionId, laneId, laneExternalId, "screen-done", "COMPLETED",
				"op-a", completedAt.minusSeconds(120), completedAt);
	}

	private long insertExecution(String visitExternalId, long laneId, String status) {
		Long id = jdbc.queryForObject("INSERT INTO execution (external_id, site_external_id, "
						+ "lane_id, status, plate, process_instance_id) OUTPUT INSERTED.execution_id "
						+ "VALUES (?, ?, ?, ?, ?, ?)",
				Long.class, visitExternalId, SITE, laneId, status, "PLATE-" + laneId,
				"proc-" + visitExternalId);
		return id == null ? -1L : id;
	}

	private void insertWorkItem(String externalId, long executionId, long laneId,
			String laneExternalId, String screenExternalId, String status, String assignee,
			Instant queuedAt, Instant completedAt) {
		jdbc.update("INSERT INTO work_item (external_id, site_external_id, execution_id, lane_id, "
						+ "visit_external_id, lane_external_id, process_instance_id, task_id, "
						+ "process_definition_key, node_reference, screen_external_id, status, assignee, "
						+ "queued_at, started_at, completed_at, completion_duration_sec) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				externalId, SITE, executionId, laneId, "visit-" + externalId, laneExternalId,
				"proc-" + externalId, "task-" + externalId, "proc-grid", "node-grid",
				screenExternalId, status, assignee, timestamp(queuedAt),
				completedAt == null ? null : timestamp(queuedAt.plusSeconds(60)),
				timestamp(completedAt), completedAt == null ? null : 60);
	}

	private void insertLaneMonitor(long laneId, String laneExternalId, int priority,
			String trafficStatus, String trafficColor, String visitExternalId, Instant slaBreachedAt,
			boolean outOfService) {
		jdbc.update("INSERT INTO lane_monitor (site_external_id, site_code, site_is_primary, "
						+ "lane_id, lane_external_id, lane_code, lane_name, lane_priority, "
						+ "is_out_of_service, traffic_status, traffic_color, visit_external_id, "
						+ "queued_work_item_external_id, queued_work_item_queued_at, "
						+ "queued_work_item_sla_breached_at) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				SITE, SITE, laneId, laneExternalId, laneExternalId, "Lane " + laneId, priority,
				outOfService, trafficStatus, trafficColor, visitExternalId,
				slaBreachedAt == null ? null : "wi-" + laneExternalId,
				timestamp(slaBreachedAt == null ? null : Instant.now().minusSeconds(300)),
				timestamp(slaBreachedAt));
	}

	private static List<String> reasons(LaneAlert alert) {
		return alert.getAlertReasons().stream().map(LaneAlertReason::getValue).toList();
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
