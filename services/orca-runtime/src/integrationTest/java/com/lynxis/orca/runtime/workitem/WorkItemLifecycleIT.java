package com.lynxis.orca.runtime.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.web.ApiException;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.execution.domain.AdmissionService;
import com.lynxis.orca.runtime.execution.domain.InboundDeviceEvent;
import com.lynxis.orca.runtime.execution.domain.LaneResetService;
import com.lynxis.orca.runtime.execution.domain.DeviceCommandPort;
import com.lynxis.orca.runtime.integration.api.ConnectorPort;
import com.lynxis.orca.runtime.workitem.api.WorkItemController;
import com.lynxis.orca.runtime.workitem.api.WorkItemErrorCode;
import com.lynxis.orca.runtime.workitem.domain.WorkItemService;

/**
 * <strong>Runs the three work-item lifecycle inversions rather than merely asserting them.</strong>
 *
 * <ol>
 *   <li><strong>Creation is the engine's transaction</strong> — the item and the
 *       parked wait state appear together, and a fault between them rolls back
 *       BOTH ({@link #createAndParkAreAtomicUnderAFault}).</li>
 *   <li><strong>Completion advances the process atomically</strong> — by the time
 *       {@code complete} returns the visit is closed, and a fault inside the
 *       completing transaction leaves the item held and the process parked
 *       ({@link #completeAndAdvanceAreAtomicUnderAFault}).</li>
 *   <li><strong>An out-of-order submit is refused</strong> — a step the engine is
 *       not waiting on refuses the submit and the item update rolls back with it
 *       ({@link #anOutOfOrderSubmitIsRefusedAndNothingMoves}).</li>
 * </ol>
 *
 * <p>Plus the claim race at admission's own scale: two operators, one item, 1,000
 * iterations, exactly one winner each time — the conditional UPDATE is the guard
 * and nothing else is.
 *
 * <p>The connector stub is DOWN for the whole suite, so every admitted truck takes
 * the failure branch into the manual-input wait state — which is exactly the shape
 * this phase builds for.
 */
@SpringBootTest(
		classes = { RuntimeApplication.class, WorkItemLifecycleIT.StubPorts.class },
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + WorkItemLifecycleIT.SCHEMA,
				"spring.flyway.schemas=" + WorkItemLifecycleIT.SCHEMA,
				"spring.flyway.default-schema=" + WorkItemLifecycleIT.SCHEMA,
				"orca.required-views=",
				"flowable.async-executor-activate=true",
				"orca.installation.site-external-id=" + WorkItemLifecycleIT.SITE,
				"orca.internal.shared-credential=integration-test-credential-not-a-fixture",
		})
@org.springframework.test.annotation.DirtiesContext(
		classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class WorkItemLifecycleIT {

	static final String SCHEMA = "runtime";
	static final String SITE = "SITE-IT";
	static final String LANE = "LANE-IT-01";

	private static final Logger log = LoggerFactory.getLogger(WorkItemLifecycleIT.class);

	@Autowired
	private AdmissionService admission;

	@Autowired
	private WorkItemService workItems;

	@Autowired
	private LaneResetService laneReset;

	@Autowired
	private RuntimeService runtimeService;

	@Autowired
	private TaskService taskService;

	@Autowired
	private ManagementService managementService;

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
	}

	@BeforeEach
	void freshState() {
		jdbc = new JdbcTemplate(dataSource);
		// Parked instances from earlier tests hold engine state; remove them first
		// so the platform tables can be emptied under their foreign keys.
		runtimeService.createProcessInstanceQuery().list().forEach(instance ->
				runtimeService.deleteProcessInstance(instance.getId(), "test cleanup"));
		for (String table : List.of("work_item_audit", "work_item", "outbox_delivery", "outbox",
				"execution_event", "execution", "lane_session", "idempotency_record")) {
			jdbc.execute("DELETE FROM " + table);
		}
		for (String table : List.of("topology_screen", "topology_team_routing",
				"topology_team_member", "topology_operator", "topology_setting")) {
			admin("DELETE FROM core." + table);
		}
	}

	// ------------------------------------------------------------------------
	// Inversion 1 — creation in the engine's transaction
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a process reaching the manual step queues the item and parks the engine — one transaction, both visible together")
	void theItemAndTheWaitStateAppearTogether() {
		// A screen identity fronts the node, so creation resolves it transactionally.
		admin("INSERT INTO core.topology_screen (screen_external_id, screen_name, "
				+ "process_definition_key, node_reference, max_sec, site_external_id) "
				+ "VALUES ('scr-manual', N'Manual handling', 'gate-visit', 'manualInput', 300, '"
				+ SITE + "')");

		String visit = admitAndPark();
		WorkItemRow item = queuedItemOf(visit);

		assertThat(item.status()).isEqualTo("QUEUED");
		assertThat(item.visitExternalId()).isEqualTo(visit);
		assertThat(item.laneExternalId()).isEqualTo(LANE);
		assertThat(item.processDefinitionKey()).isEqualTo("gate-visit");
		assertThat(item.nodeReference()).isEqualTo("manualInput");
		assertThat(jdbc.queryForObject("SELECT screen_external_id FROM work_item WHERE external_id = ?",
				String.class, item.externalId()))
				.as("the screen identity resolved at creation, in the creating transaction")
				.isEqualTo("scr-manual");

		// The engine task the item parks on genuinely exists and is the wait state.
		assertThat(taskService.createTaskQuery().taskId(item.taskId()).count())
				.as("the item's task_id is the engine's own handle, not a copy that can drift")
				.isEqualTo(1);
		assertThat(statusOf(visit))
				.as("the truck is still standing at the gate; the visit stays ACTIVE while a person works")
				.isEqualTo("ACTIVE");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a fault between create-item and park rolls back BOTH — then the retry produces both together")
	void createAndParkAreAtomicUnderAFault() {
		poisonTable("work_item");
		String visit;
		try {
			// The admission itself succeeds — the visit starts, the connector job is
			// queued. It is the LATER engine transaction, the one entering the wait
			// state, that the poison kills: the listener's insert throws, the whole
			// job rolls back, and after three retries it dead-letters.
			visit = admit();

			awaitTrue("the poisoned job dead-letters", () ->
					managementService.createDeadLetterJobQuery().count() > 0);

			assertThat(count("SELECT COUNT(*) FROM work_item"))
					.as("no item exists — and neither does the wait state: the two halves rolled back together")
					.isZero();
			assertThat(taskService.createTaskQuery().count())
					.as("no state where the process parked and no item was queued")
					.isZero();
		}
		finally {
			unpoisonTable("work_item");
		}

		// Heal: push the dead-lettered job back. The SAME transaction now succeeds
		// and both halves appear together — nothing was half-applied to clean up.
		managementService.createDeadLetterJobQuery().list().forEach(job ->
				managementService.moveDeadLetterJobToExecutableJob(job.getId(), 3));

		WorkItemRow item = queuedItemOf(visit);
		assertThat(taskService.createTaskQuery().taskId(item.taskId()).count()).isEqualTo(1);
		log.info("WP1 · the retried transaction produced item {} and task {} together",
				item.externalId(), item.taskId());
	}

	// ------------------------------------------------------------------------
	// The claim — one winner, at scale
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 20, unit = TimeUnit.MINUTES)
	@DisplayName("two operators claim the same item at the same instant: exactly one wins, 1,000 times out of 1,000")
	void twoOperatorsRacingTheClaimProduceExactlyOneWinner() throws Exception {
		long executionId = fakeExecutionRow();
		ExecutorService racers = Executors.newFixedThreadPool(2);
		try {
			for (int i = 0; i < 1_000; i++) {
				String itemId = "wi-race-" + i;
				insertQueuedItem(itemId, executionId, "task-race-" + i);

				CyclicBarrier line = new CyclicBarrier(2);
				Future<Boolean> a = racers.submit(claim(line, itemId, "op-a"));
				Future<Boolean> b = racers.submit(claim(line, itemId, "op-b"));

				int winners = (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
				assertThat(winners)
						.as("iteration %d: the conditional UPDATE admits exactly one claimant", i)
						.isEqualTo(1);
			}
		}
		finally {
			racers.shutdownNow();
		}

		assertThat(count("SELECT COUNT(*) FROM work_item WHERE status = 'IN_PROGRESS'"))
				.isEqualTo(1_000);
		assertThat(count("SELECT COUNT(*) FROM work_item_audit WHERE action = 'TAKE'"))
				.as("one TAKE audit row per item — the loser wrote nothing")
				.isEqualTo(1_000);
	}

	private Callable<Boolean> claim(CyclicBarrier line, String itemExternalId, String operator) {
		return () -> {
			line.await(10, TimeUnit.SECONDS);
			try {
				inScope(() -> workItems.take(itemExternalId, operator));
				return true;
			}
			catch (WorkItemService.WorkItemConflictException lost) {
				return false;
			}
		};
	}

	// ------------------------------------------------------------------------
	// Inversions 2 and 3 — complete-and-advance, and the out-of-order refusal
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("completing the item advances the process in the SAME transaction — the visit is closed before complete() returns")
	void completionAdvancesTheProcessAtomically() {
		String visit = admitAndPark();
		WorkItemRow item = queuedItemOf(visit);

		WorkItemController clerk = controllerFor("op-clerk");
		clerk.takeWorkItem(item.externalId());
		var completed = clerk.completeWorkItem(item.externalId(),
				new com.lynxis.orca.runtime.api.generated.model.CompleteWorkItemRequest()
						.correctedEventData("{\"decision\":\"corrected\"}")).getBody().getData();

		// No polling anywhere below: everything asserted here committed inside the
		// one transaction complete() ran.
		assertThat(completed.getStatus().getValue()).isEqualTo("COMPLETED");
		assertThat(completed.getCompletionDurationSec())
				.as("server-computed, never taken from the request")
				.isNotNull();
		assertThat(statusOf(visit))
				.as("the parked process resumed and reached its end state in the completing transaction")
				.isEqualTo("MANUAL");
		assertThat(taskService.createTaskQuery().taskId(item.taskId()).count()).isZero();
		assertThat(runtimeService.createProcessInstanceQuery()
				.processInstanceBusinessKey(visit).count()).isZero();

		assertThat(jdbc.queryForList("SELECT action FROM work_item_audit wa JOIN work_item w "
						+ "ON w.work_item_id = wa.work_item_id WHERE w.external_id = ? ORDER BY wa.occurred_at",
				String.class, item.externalId()))
				.containsExactly("TAKE", "COMPLETE");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a fault inside the completing transaction leaves the item HELD and the process PARKED — neither half applies")
	void completeAndAdvanceAreAtomicUnderAFault() {
		String visit = admitAndPark();
		WorkItemRow item = queuedItemOf(visit);
		inScope(() -> workItems.take(item.externalId(), "op-clerk"));

		// The audit write is the LAST write of the completing transaction — after
		// the item's update and after the engine already advanced inside this
		// transaction. Poisoning it proves the whole thing unwinds: the engine's
		// advance is rolled back too, which is the strongest form of the claim.
		poisonTable("work_item_audit");
		try {
			assertThatThrownBy(() -> inScope(() ->
					workItems.complete(item.externalId(), "op-clerk", null)))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
		}
		finally {
			unpoisonTable("work_item_audit");
		}

		assertThat(itemStatus(item.externalId()))
				.as("the item's COMPLETED update rolled back with the fault")
				.isEqualTo("IN_PROGRESS");
		assertThat(taskService.createTaskQuery().taskId(item.taskId()).count())
				.as("and the engine still parks — its advance rolled back in the same transaction")
				.isEqualTo(1);
		assertThat(statusOf(visit)).isEqualTo("ACTIVE");

		// Unpoisoned, the same call succeeds whole.
		inScope(() -> workItems.complete(item.externalId(), "op-clerk", null));
		assertThat(statusOf(visit)).isEqualTo("MANUAL");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("a submit for a step the engine is not waiting on is REFUSED, and the item update rolls back with it")
	void anOutOfOrderSubmitIsRefusedAndNothingMoves() {
		// An item whose task the engine never held — the decoupled-halves state 1.x
		// could not even detect. 2.0 refuses it typed.
		long executionId = fakeExecutionRow();
		insertQueuedItem("wi-stale", executionId, "task-the-engine-never-heard-of");
		inScope(() -> workItems.take("wi-stale", "op-clerk"));

		WorkItemController clerk = controllerFor("op-clerk");
		assertThatThrownBy(() -> clerk.completeWorkItem("wi-stale", null))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code())
								.isEqualTo(WorkItemErrorCode.WORK_ITEM_OUT_OF_ORDER.code()));

		assertThat(itemStatus("wi-stale"))
				.as("the completion was refused WHOLE — the item did not move")
				.isEqualTo("IN_PROGRESS");
		assertThat(count("SELECT COUNT(*) FROM work_item_audit WHERE action = 'COMPLETE'"))
				.isZero();

		// And the second face of the same inversion: an item already completed
		// refuses a second submit as a state conflict.
		String visit = admitAndPark();
		WorkItemRow item = queuedItemOf(visit);
		clerk.takeWorkItem(item.externalId());
		clerk.completeWorkItem(item.externalId(), null);
		assertThatThrownBy(() -> clerk.completeWorkItem(item.externalId(), null))
				.isInstanceOfSatisfying(ApiException.class, refusal ->
						assertThat(refusal.getErrorCode().code())
								.isEqualTo(WorkItemErrorCode.WORK_ITEM_CONFLICT.code()));
	}

	// ------------------------------------------------------------------------
	// The rest of the lifecycle — takeover, park, assign, and lane reset
	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("takeover reassigns and resets the clock; park re-queues; assign reserves — each guarded, each audited")
	void theRemainingActionsHoldTheirGuards() {
		long executionId = fakeExecutionRow();
		insertQueuedItem("wi-flow", executionId, "task-flow");

		inScope(() -> workItems.take("wi-flow", "op-a"));

		// Takeover: op-b steals, op-a's tenure lands in the audit row.
		var afterTakeover = inScope(() -> workItems.takeover("wi-flow", "op-b"));
		assertThat(afterTakeover.assignee()).isEqualTo("op-b");
		assertThat(afterTakeover.status()).isEqualTo("IN_PROGRESS");

		// Park: back to the queue, nobody holds it, the clock is gone.
		var afterPark = inScope(() -> workItems.park("wi-flow", "op-b"));
		assertThat(afterPark.status()).isEqualTo("QUEUED");
		assertThat(afterPark.assignee()).isNull();
		assertThat(afterPark.startedAt()).isNull();

		// Park by someone who does not hold it: a typed conflict, not a no-op.
		inScope(() -> workItems.take("wi-flow", "op-c"));
		assertThatThrownBy(() -> inScope(() -> workItems.park("wi-flow", "op-a")))
				.isInstanceOf(WorkItemService.WorkItemConflictException.class);
		inScope(() -> workItems.park("wi-flow", "op-c"));

		// Assign reserves the claim: only the assignee can take a pre-assigned item.
		inScope(() -> workItems.assign("wi-flow", "op-d", "op-supervisor"));
		assertThatThrownBy(() -> inScope(() -> workItems.take("wi-flow", "op-e")))
				.as("a pre-assigned item is not claimable by someone else")
				.isInstanceOf(WorkItemService.WorkItemConflictException.class);
		var takenByAssignee = inScope(() -> workItems.take("wi-flow", "op-d"));
		assertThat(takenByAssignee.status()).isEqualTo("IN_PROGRESS");

		assertThat(jdbc.queryForList("SELECT action FROM work_item_audit wa JOIN work_item w "
						+ "ON w.work_item_id = wa.work_item_id WHERE w.external_id = 'wi-flow' "
						+ "ORDER BY wa.work_item_audit_id", String.class))
				.containsExactly("TAKE", "TAKE_OVER", "PARK", "TAKE", "PARK", "ASSIGN", "TAKE");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	@DisplayName("lane reset fails the visit AND its open work item together, and frees the lane for the next truck")
	void laneResetFailsTheVisitAndItsItemsTogether() {
		String visit = admitAndPark();
		WorkItemRow item = queuedItemOf(visit);
		inScope(() -> workItems.take(item.externalId(), "op-clerk"));

		LaneResetService.LaneReset reset = inScope(() -> laneReset.reset(LANE, "op-supervisor"));

		assertThat(reset.visitExternalId()).isEqualTo(visit);
		assertThat(reset.failedWorkItems()).isEqualTo(1);
		assertThat(statusOf(visit)).isEqualTo("FAILED");
		assertThat(itemStatus(item.externalId()))
				.as("the sheet's §2: FAILED has a writer, and this is it")
				.isEqualTo("FAILED");
		assertThat(taskService.createTaskQuery().taskId(item.taskId()).count()).isZero();

		// The filtered unique index held the lane while the visit was ACTIVE;
		// the reset freed it — the next truck starts a NEW visit.
		String next = admitAndPark();
		assertThat(next).isNotEqualTo(visit);

		// A reset with nothing to reset is an ordinary outcome, not an error —
		// but it must not abort the newly admitted visit's predecessor twice.
		inScope(() -> laneReset.reset(LANE, "op-supervisor"));
	}

	// ------------------------------------------------------------------------

	/** Admits one truck; the downed connector routes it into the wait state. */
	private String admit() {
		AdmissionService.EventOutcome outcome = inScope(() ->
				admission.accept(new InboundDeviceEvent("evt-" + UUID.randomUUID(), LANE,
						"DEV-IT-CAMERA", "lpr.capture", "{\"plate\":\"T-WI-01\"}", null)));
		return outcome.visitExternalId();
	}

	private String admitAndPark() {
		String visit = admit();
		queuedItemOf(visit);
		return visit;
	}

	/** Waits for the creating transaction to commit, then reads the item it wrote. */
	private WorkItemRow queuedItemOf(String visitExternalId) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
		while (System.nanoTime() < deadline) {
			List<WorkItemRow> rows = jdbc.query(
					"SELECT external_id, visit_external_id, lane_external_id, process_definition_key, "
							+ "node_reference, task_id, status FROM work_item WHERE visit_external_id = ?",
					(rs, n) -> new WorkItemRow(rs.getString("external_id"),
							rs.getString("visit_external_id"), rs.getString("lane_external_id"),
							rs.getString("process_definition_key"), rs.getString("node_reference"),
							rs.getString("task_id"), rs.getString("status")),
					visitExternalId);
			if (!rows.isEmpty()) {
				return rows.getFirst();
			}
			sleep(200);
		}
		throw new AssertionError("no work item ever appeared for visit " + visitExternalId);
	}

	private WorkItemController controllerFor(String operator) {
		return new WorkItemController(workItems, () -> Optional.of(operator), SITE);
	}

	private long fakeExecutionRow() {
		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status) "
				+ "VALUES (?, ?, 999, 'MANUAL')", "vis-fake-" + UUID.randomUUID(), SITE);
		return jdbc.queryForObject("SELECT MAX(execution_id) FROM execution", Long.class);
	}

	private void insertQueuedItem(String externalId, long executionId, String taskId) {
		jdbc.update("INSERT INTO work_item (external_id, site_external_id, execution_id, lane_id, "
						+ "visit_external_id, lane_external_id, process_instance_id, task_id, "
						+ "process_definition_key, node_reference, status) "
						+ "VALUES (?, ?, ?, 999, 'vis-fake', ?, 'pi-fake', ?, 'gate-visit', 'manualInput', 'QUEUED')",
				externalId, SITE, executionId, LANE, taskId);
	}

	private String statusOf(String visitExternalId) {
		return jdbc.queryForObject("SELECT status FROM execution WHERE external_id = ?",
				String.class, visitExternalId);
	}

	private String itemStatus(String itemExternalId) {
		return jdbc.queryForObject("SELECT status FROM work_item WHERE external_id = ?",
				String.class, itemExternalId);
	}

	private long count(String sql) {
		Long counted = jdbc.queryForObject(sql, Long.class);
		return counted == null ? 0 : counted;
	}

	private <T> T inScope(java.util.function.Supplier<T> action) {
		return ScopeContext.callIn(Scope.of("site_external_id", java.util.Set.of(SITE)), action::get);
	}

	private void awaitTrue(String what, java.util.function.BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			sleep(250);
		}
		throw new AssertionError("never observed: " + what);
	}

	/**
	 * The fault injection: an INSTEAD OF INSERT trigger that refuses every insert,
	 * so the write the transaction needs cannot happen — a database-level fault the
	 * application cannot see coming, which is exactly what a crash between two
	 * writes looks like from inside.
	 */
	private void poisonTable(String table) {
		admin("CREATE TRIGGER runtime.tr_poison_" + table + " ON runtime." + table
				+ " INSTEAD OF INSERT AS BEGIN THROW 50001, 'poisoned by WorkItemLifecycleIT', 1; END");
	}

	private void unpoisonTable(String table) {
		admin("DROP TRIGGER runtime.tr_poison_" + table);
	}

	private static void publishTopologyLane() {
		admin("IF SCHEMA_ID(N'core') IS NULL EXEC('CREATE SCHEMA [core]')");
		admin("IF OBJECT_ID(N'core.topology_lane', 'V') IS NOT NULL DROP VIEW core.topology_lane");
		admin("EXEC('CREATE VIEW core.topology_lane AS SELECT CAST(1 AS BIGINT) AS lane_id, "
				+ "''" + LANE + "'' AS lane_external_id, ''" + SITE + "'' AS site_external_id')");
		RoutingTopologyFixture.publish(WorkItemLifecycleIT::admin, "it_" + SCHEMA);
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

	private record WorkItemRow(String externalId, String visitExternalId, String laneExternalId,
			String processDefinitionKey, String nodeReference, String taskId, String status) {
	}

	/**
	 * The connector is DOWN for this whole suite — every truck takes the failure
	 * branch into the manual-input wait state, which is the branch under test.
	 */
	@Configuration(proxyBeanMethods = false)
	static class StubPorts {

		static final AtomicBoolean connectorDown = new AtomicBoolean(true);

		@Bean
		@Primary
		ConnectorPort downConnector() {
			return call -> {
				if (connectorDown.get()) {
					throw new ConnectorPort.ConnectorUnavailableException("down for the whole suite");
				}
				return "APPROVED";
			};
		}

		@Bean
		@Primary
		DeviceCommandPort idleDevicePort() {
			return command -> DeviceCommandPort.EXECUTED;
		}
	}
}
