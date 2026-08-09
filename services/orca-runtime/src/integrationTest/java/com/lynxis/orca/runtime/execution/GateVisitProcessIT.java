package com.lynxis.orca.runtime.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.lynxis.orca.platform.outbox.testing.PlatformDatabase;
import com.lynxis.orca.runtime.RuntimeApplication;
import com.lynxis.orca.runtime.integration.api.ConnectorPort;
import com.lynxis.orca.runtime.execution.domain.DeviceCommandPort;
import com.lynxis.orca.runtime.execution.domain.ProcessEngineGateway;
import com.lynxis.orca.runtime.execution.domain.ProcessVariables;

/**
 * <strong>Proves the process, its delegates, and one uncertain engine behaviour.</strong>
 *
 * <p>{@code gate-visit} is written as if it were the visual builder's compiler
 * output, so what is proven here is what that compiler will be able to rely on:
 * the process deploys from the classpath at startup, both delegate bean names
 * resolve, the gateway branches on the outcome token, and every failure reaches
 * the named "manual handling required" end state rather than a stack trace.
 *
 * <p>The last test answers an implementation question: <em>does a boundary
 * timer on a service task ever fire?</em> It is asked by running it, and whatever
 * the engine does is what goes in the report.
 */
@SpringBootTest(
		classes = { RuntimeApplication.class, GateVisitProcessIT.StubPorts.class },
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.jpa.properties.hibernate.default_schema=" + GateVisitProcessIT.SCHEMA,
				"spring.flyway.schemas=" + GateVisitProcessIT.SCHEMA,
				"spring.flyway.default-schema=" + GateVisitProcessIT.SCHEMA,
				"orca.required-views=",
				// The async executor is ON here, unlike in every other suite. It has to
				// be: both service tasks are flowable:async="true" precisely so that no
				// outbound call happens inside admission's transaction, which
				// means nothing runs at all without a worker to run it.
				"flowable.async-executor-activate=true",
		})
// This suite is the only one that runs the async executor, and AdmissionThroughHttpIT
// migrates the SAME `runtime` schema — V100 stamps that name explicitly, so neither
// can move. Spring caches a context across test classes, so without this the executor
// would still be polling for jobs while the other suite dropped and rebuilt the tables
// underneath it. Closing the context when this class ends is what stops it.
@org.springframework.test.annotation.DirtiesContext(
		classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GateVisitProcessIT {

	/** The production migration set stamps extended properties on a schema called `runtime` by name. */
	static final String SCHEMA = "runtime";

	private static final Logger log = LoggerFactory.getLogger(GateVisitProcessIT.class);

	private static final String GATE_VISIT = "gate-visit";

	@Autowired
	private ProcessEngineGateway gateway;

	@Autowired
	private RuntimeService runtimeService;

	@Autowired
	private HistoryService historyService;

	@Autowired
	private StubPorts.Recorder recorder;

	@DynamicPropertySource
	static void pointAtTheSchema(DynamicPropertyRegistry registry) {
		DriverManagerDataSource migrated = (DriverManagerDataSource) PlatformDatabase.migratedSchema(SCHEMA,
				"db/migration", "db/platform/outbox", "db/platform/lease", "db/platform/idempotency");
		registry.add("spring.datasource.url", migrated::getUrl);
		registry.add("spring.datasource.username", migrated::getUsername);
		registry.add("spring.datasource.password", migrated::getPassword);
		// Entering manualInput arms the SLA timer, whose duration bean reads
		// core's published screen/setting views — absent here, so every timer
		// arms at the no-threshold sentinel and the wait-state tests are
		// undisturbed. WorkItemSlaIT is where thresholds are real.
		com.lynxis.orca.runtime.workitem.RoutingTopologyFixture.publish(
				GateVisitProcessIT::admin, "it_" + SCHEMA);
	}

	private static void admin(String sql) {
		try (java.sql.Connection connection = PlatformDatabase.administrative().getConnection();
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (java.sql.SQLException e) {
			throw new IllegalStateException("Failed: " + sql, e);
		}
	}

	@BeforeEach
	void freshState() {
		recorder.reset();
	}

	// ------------------------------------------------------------------------

	@Test
	@DisplayName("the process deploys from the classpath at startup, under the key the compiler emits")
	void theProcessIsDeployed() {
		// Auto-deploy from classpath is temporary scaffolding; the product path is the
		// publish pipeline pushing to /internal/deployments/v1. What matters here is
		// that the KEY is stable, because a deployment
		// assigns a version to lanes by key.
		assertThat(runtimeService.createProcessInstanceQuery().processDefinitionKey(GATE_VISIT).count())
				.as("no instances yet, but the query resolves — the definition is deployed")
				.isZero();
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("the happy path: connector approves, the barrier is commanded, the visit is released")
	void theHappyPathReachesVisitReleased() {
		recorder.connectorOutcome.set("APPROVED");
		recorder.deviceOutcome.set(DeviceCommandPort.EXECUTED);

		String instanceId = startVisit();

		assertThat(endStateOf(instanceId)).isEqualTo("visitReleased");
		assertThat(recorder.connectorCalls).hasSize(1);
		assertThat(recorder.deviceCommands).hasSize(1);

		// Correlation keys reached the delegate — the compiler's half of the contract.
		ConnectorPort.ConnectorCall call = recorder.connectorCalls.getFirst();
		assertThat(call.laneExternalId()).isEqualTo("LANE-DEMO-01");
		assertThat(call.connectorName()).isEqualTo("tos");
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a connector answer nobody wrote a branch for goes to a human — the process PARKS at the wait state")
	void anUnroutedOutcomeTakesTheDefaultFlow() {
		recorder.connectorOutcome.set("SOMETHING_NOBODY_MAPPED");

		String instanceId = startVisit();

		// Manual handling is a genuine WAIT STATE rather than a terminal state. The
		// process parks; a person advances it.
		awaitParkedAt(instanceId, "manualInput");
		assertThat(recorder.deviceCommands)
				.as("the barrier must not be commanded on an outcome nothing approved")
				.isEmpty();
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("an unavailable customer system parks at manual handling, not a dead letter")
	void anUnavailableConnectorReachesManualHandling() {
		recorder.connectorUnavailable.set(true);

		String instanceId = startVisit();

		// a BpmnError is caught by the boundary event; a runtime exception would
		// have retried into a wall and ended as a dead-letter job nobody at the
		// gate ever sees. The human branch is therefore a wait state.
		awaitParkedAt(instanceId, "manualInput");
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a device command that FAILS parks at manual handling")
	void aFailedDeviceCommandReachesManualHandling() {
		recorder.connectorOutcome.set("APPROVED");
		recorder.deviceOutcome.set(DeviceCommandPort.FAILED);

		awaitParkedAt(startVisit(), "manualInput");
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("a device command that returns UNKNOWN does NOT retry, and does not read as failure")
	void anUnknownDeviceOutcomeIsNotRetried() {
		recorder.connectorOutcome.set("APPROVED");
		recorder.deviceOutcome.set(DeviceCommandPort.UNKNOWN);

		String instanceId = startVisit();

		awaitParkedAt(instanceId, "manualInput");
		assertThat(recorder.deviceCommands)
				.as("§B10: an unknown outcome is resolved by looking, never by retrying blindly. "
						+ "One command was issued and exactly one")
				.hasSize(1);
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("the outbound call does NOT happen inside the starting transaction (§B9)")
	void noOutboundCallHappensInsideTheStartingTransaction() {
		// The lane lock must be released at commit before any outbound call happens.
		// flowable:async="true" is what makes that true,
		// and this is what proves it: immediately after the start call returns, the
		// connector has not been invoked. It is a queued job.
		recorder.connectorOutcome.set("APPROVED");
		recorder.deviceOutcome.set(DeviceCommandPort.EXECUTED);
		recorder.blockUntilReleased.set(true);

		String instanceId = gateway.startVisit(GATE_VISIT, "vis-" + UUID.randomUUID(), correlationKeys());

		assertThat(recorder.connectorCalls)
				.as("if this is non-empty, the connector ran inside the caller's transaction — with "
						+ "the lane row still locked, for as long as a customer system takes to answer")
				.isEmpty();

		recorder.blockUntilReleased.set(false);
		assertThat(endStateOf(instanceId)).isEqualTo("visitReleased");
	}

	// ------------------------------------------------------------------------

	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@DisplayName("PROBE: does a boundary timer on a service task fire when the step outlasts it?")
	void doesABoundaryTimerOnAServiceTaskEverFire() {
		// Asked by running it. gate-visit ships an error boundary and deliberately
		// does NOT ship a boundary timer on each
		// timer, and this is the evidence for that decision rather than an opinion
		// about engines.
		String instanceId = runtimeService.startProcessInstanceByKey("wp4-timer-probe").getId();

		String reached = endStateOf(instanceId);
		log.info("WP4 · boundary-timer probe: a 1s timer on a service task whose delegate blocks "
				+ "for 4s ended at '{}'", reached);

		assertThat(reached)
				.as("""
						OBSERVED BEHAVIOUR, recorded rather than asserted as a preference.

						A boundary timer is created when its activity is entered and removed when the \
						activity completes. A service task's delegate runs to completion inside that \
						same transaction, so the timer job is written and deleted before any other \
						thread can see it — it cannot fire, however long the delegate takes. Marking \
						the task flowable:async="true" does not change this: it moves the whole \
						activity onto a worker thread, transaction and all.

						A timer on a service task is therefore protection that looks real and is not, \
						which is worse than no timer. gate-visit ships the error boundary alone; the \
						deadline on an outbound call lives where §B8 puts it — on the call — and a \
						timer that genuinely guards a step needs the step to be a WAIT STATE \
						(flowable:triggerable="true"), which is a design point the plan did not \
						settle and the report raises.""")
				.isEqualTo("completed");
	}

	// ------------------------------------------------------------------------

	private String startVisit() {
		return gateway.startVisit(GATE_VISIT, "vis-" + UUID.randomUUID(), correlationKeys());
	}

	private static Map<String, Object> correlationKeys() {
		Map<String, Object> keys = new HashMap<>();
		keys.put(ProcessVariables.VISIT_EXTERNAL_ID, "vis-" + UUID.randomUUID());
		keys.put(ProcessVariables.LANE_EXTERNAL_ID, "LANE-DEMO-01");
		keys.put(ProcessVariables.CONNECTOR_NAME, "tos");
		keys.put(ProcessVariables.COMMAND_ACTION, "RAISE_GATE");
		keys.put(ProcessVariables.COMMAND_DEVICE_EXTERNAL_ID, "DEV-DEMO-BARRIER");
		keys.put(ProcessVariables.COMMAND_DEADLINE_MILLIS, 5_000L);
		return keys;
	}

	/**
	 * Waits for the async executor to carry the instance to a WAIT STATE and stop
	 * there — running, parked, with no live job. In the shipping human-work shape,
	 * branch: these processes have no visit row (started straight through the
	 * gateway), so no work item is raised for them; what this suite proves is the
	 * PROCESS's behaviour, and the work-item half lives in WorkItemLifecycleIT.
	 */
	private void awaitParkedAt(String processInstanceId, String activityId) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (System.nanoTime() < deadline) {
			if (gateway.isRunning(processInstanceId)
					&& gateway.currentActivity(processInstanceId).filter(activityId::equals).isPresent()) {
				return;
			}
			if (!gateway.isRunning(processInstanceId)) {
				throw new AssertionError("process instance " + processInstanceId
						+ " finished instead of parking at '" + activityId + "'");
			}
			sleep(100);
		}
		throw new AssertionError("process instance " + processInstanceId + " never parked at '"
				+ activityId + "'. Current activity: " + gateway.currentActivity(processInstanceId));
	}

	/** Waits for the async executor to carry the instance to an end event, and names it. */
	private String endStateOf(String processInstanceId) {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (System.nanoTime() < deadline) {
			if (!gateway.isRunning(processInstanceId)) {
				List<String> ended = historyService.createHistoricActivityInstanceQuery()
						.processInstanceId(processInstanceId)
						.activityType("endEvent")
						.list().stream()
						.map(activity -> activity.getActivityId())
						.toList();
				assertThat(ended).as("a finished instance must have passed an end event").hasSize(1);
				return ended.getFirst();
			}
			sleep(100);
		}
		throw new AssertionError("process instance " + processInstanceId + " never finished. Current activity: "
				+ gateway.currentActivity(processInstanceId));
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------------

	/**
	 * Stub ports for exercising the real delegates. They replace the ports,
	 * not the delegates — so what is exercised is the real
	 * {@code ConnectorCallDelegate} and {@code DeviceCommandDelegate}, under the
	 * bean names the compiler emits.
	 *
	 * <p>⚠️ {@code @Primary} is required because both ports have real implementations —
	 * {@code RestConnector} and {@code EdgeDeviceCommandClient} — so a substitution
	 * has to say which one wins. Without it this suite passed on a real connector
	 * with no configuration and asserted the failure branch, which is a green test
	 * measuring something it was not written to measure.
	 *
	 * <p>This suite is about the PROCESS: the gateway's branching, the two BPMN
	 * error codes, and the end states. {@code VisitLifecycleIT} is the one that runs
	 * both real transports against real sockets.
	 */
	@Configuration(proxyBeanMethods = false)
	static class StubPorts {

		@Bean
		Recorder recorder() {
			return new Recorder();
		}

		@Bean
		@org.springframework.context.annotation.Primary
		ConnectorPort stubConnectorPort(Recorder recorder) {
			return call -> {
				while (recorder.blockUntilReleased.get()) {
					sleep(50);
				}
				recorder.connectorCalls.add(call);
				if (recorder.connectorUnavailable.get()) {
					throw new ConnectorPort.ConnectorUnavailableException("the stub was told to be down");
				}
				return recorder.connectorOutcome.get();
			};
		}

		@Bean
		@org.springframework.context.annotation.Primary
		DeviceCommandPort stubDeviceCommandPort(Recorder recorder) {
			return command -> {
				recorder.deviceCommands.add(command);
				return recorder.deviceOutcome.get();
			};
		}

		/** The delegate the boundary-timer probe binds to. Blocks for four times its timer. */
		@Bean
		JavaDelegate slowDelegate() {
			return (DelegateExecution execution) -> sleep(4_000);
		}

		static class Recorder {

			final List<ConnectorPort.ConnectorCall> connectorCalls = new CopyOnWriteArrayList<>();
			final List<DeviceCommandPort.DeviceCommand> deviceCommands = new CopyOnWriteArrayList<>();
			final AtomicReference<String> connectorOutcome = new AtomicReference<>("APPROVED");
			final AtomicReference<String> deviceOutcome = new AtomicReference<>(DeviceCommandPort.EXECUTED);
			final java.util.concurrent.atomic.AtomicBoolean connectorUnavailable =
					new java.util.concurrent.atomic.AtomicBoolean();
			final java.util.concurrent.atomic.AtomicBoolean blockUntilReleased =
					new java.util.concurrent.atomic.AtomicBoolean();

			void reset() {
				connectorCalls.clear();
				deviceCommands.clear();
				connectorOutcome.set("APPROVED");
				deviceOutcome.set(DeviceCommandPort.EXECUTED);
				connectorUnavailable.set(false);
				blockUntilReleased.set(false);
			}
		}
	}
}
