package com.lynxis.orca.runtime.execution.engine.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.execution.delegate.OrcaConnectorDelegate;
import com.lynxis.orca.runtime.execution.delegate.OrcaDeviceEffectDelegate;
import com.lynxis.orca.runtime.execution.delegate.OrcaDisplayDelegate;
import com.lynxis.orca.runtime.execution.delegate.OrcaNotificationDelegate;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorGateway;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorRequest;
import com.lynxis.orca.runtime.execution.delegate.spi.ConnectorResult;
import com.lynxis.orca.runtime.execution.delegate.spi.EdgeClient;
import com.lynxis.orca.runtime.execution.delegate.spi.EdgeCommand;
import com.lynxis.orca.runtime.execution.delegate.spi.EdgeOutcome;
import com.lynxis.orca.runtime.execution.delegate.spi.NotificationSender;
import com.lynxis.orca.runtime.execution.engine.BpmnDefinition;
import com.lynxis.orca.runtime.execution.engine.EngineInstanceRef;
import com.lynxis.orca.runtime.execution.engine.InstanceState;
import com.lynxis.orca.runtime.execution.engine.TenantRef;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngine;
import com.lynxis.orca.runtime.execution.internal.RuntimeVisitIdentity;
import com.lynxis.orca.runtime.execution.internal.VisitDataWriter;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.persistence.NodeExecutionTraceRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitDatasetRepository;

/**
 * The compiled definitions' delegates, live: a definition in the compiler's own shape —
 * {@code n_}-prefixed elements, {@code flowable:delegateExpression} references, a response
 * gateway routing on {@code orcaResponseStatus} — runs against a real engine with the step
 * recorder listening, and every write the compiled BPMN depends on lands where the
 * selectors will read it: the routing variable, the visit's durable dataset, and the
 * step's own trace row.
 *
 * <p>The edge effects prove their contract's sharp edge too: FAILED and UNKNOWN are
 * DIFFERENT failures, because "definitely did not fire" and "cannot know" have different
 * retry policies at a gate arm.
 */
class CompiledDelegatesLiveIT {

	private static final String SITE = "SITE-DELEGATES-IT";
	private static final String WAIT = "e0e0e0e0-0000-0000-0000-000000000001";
	private static final String CONN = "e0e0e0e0-0000-0000-0000-000000000002";
	private static final String DISP = "e0e0e0e0-0000-0000-0000-000000000003";
	private static final String NOTE = "e0e0e0e0-0000-0000-0000-000000000004";
	private static final String PROCESS_KEY = "proc_980001";

	private static final AtomicLong LANES = new AtomicLong(9500);

	private static ProcessEngine processEngine;
	private static JdbcTemplate jdbc;
	private static WorkflowEngine engine;
	private static NodeExecutionTraceRepository trace;
	private static VisitDatasetRepository dataset;

	// --- the recording seams -------------------------------------------------

	private static final List<ConnectorRequest> connectorRequests = new ArrayList<>();
	private static ConnectorResult connectorAnswer = new ConnectorResult(200, Map.of());
	private static final List<EdgeCommand> edgeCommands = new ArrayList<>();
	private static EdgeOutcome edgeAnswer = EdgeOutcome.SUCCEEDED;
	private static final List<String> notified = new ArrayList<>();

	/** Set after boot; the beans map holds late-binding wrappers so boot order cannot matter. */
	private static OrcaConnectorDelegate connectorDelegate;
	private static OrcaDeviceEffectDelegate deviceDelegate;
	private static OrcaDisplayDelegate displayDelegate;
	private static OrcaNotificationDelegate notificationDelegate;

	@BeforeAll
	static void bootTheCompiledWorld() {
		// The expression beans go in BEFORE boot (the engine may copy the map), as
		// late-binding wrappers: several delegates need the engine's own services to
		// construct, so the real instances arrive right after and resolve at call time.
		Map<Object, Object> beans = new HashMap<>();
		beans.put("orcaConnectorDelegate",
				(org.flowable.engine.delegate.JavaDelegate) e -> connectorDelegate.execute(e));
		beans.put("orcaDeviceEffectDelegate",
				(org.flowable.engine.delegate.JavaDelegate) e -> deviceDelegate.execute(e));
		beans.put("orcaDisplayDelegate",
				(org.flowable.engine.delegate.JavaDelegate) e -> displayDelegate.execute(e));
		beans.put("orcaNotificationDelegate",
				(org.flowable.engine.delegate.JavaDelegate) e -> notificationDelegate.execute(e));

		FlowableTestEngines.BootedEngine booted = FlowableTestEngines.bootWithDatabase(
				"compiled_delegates", "orca_runtime_delegates", "Delegates!Live2026", 8, beans);
		processEngine = booted.engine();
		jdbc = new JdbcTemplate(new DriverManagerDataSource(
				booted.db().jdbcUrl(), booted.db().username(), booted.db().password()));

		ScopeSeam seam = new JdbcScopeSeam(jdbc);
		trace = new NodeExecutionTraceRepository(seam);
		dataset = new VisitDatasetRepository(seam);
		AdmissionRepository admission = new AdmissionRepository(seam);
		processEngine.getRuntimeService()
				.addEventListener(new NodeExecutionRecorder(trace, SITE));

		ConnectorGateway gateway = request -> {
			connectorRequests.add(request);
			return connectorAnswer;
		};
		EdgeClient edge = command -> {
			edgeCommands.add(command);
			return edgeAnswer;
		};
		NotificationSender sender = (key, site, nodeUuid, name) -> notified.add(nodeUuid + "|" + name);

		connectorDelegate = new OrcaConnectorDelegate(gateway,
				processEngine.getRuntimeService(), new VisitDataWriter(dataset, SITE),
				new RuntimeVisitIdentity(admission, SITE));
		deviceDelegate = new OrcaDeviceEffectDelegate(edge);
		displayDelegate = new OrcaDisplayDelegate(edge);
		notificationDelegate = new OrcaNotificationDelegate(sender);

		engine = new FlowableWorkflowEngine(processEngine);
		engine.deploy(new BpmnDefinition(PROCESS_KEY, COMPILED_SHAPE.getBytes(StandardCharsets.UTF_8)),
				new TenantRef(SITE));
	}

	/** The compiler's own shape: n_ ids, delegateExpressions, a status-routing gateway. */
	private static final String COMPILED_SHAPE = """
			<?xml version="1.0" encoding="UTF-8"?>
			<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
			             xmlns:flowable="http://flowable.org/bpmn"
			             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
			             targetNamespace="http://lynxis.com/orca">
			  <process id="%s" isExecutable="true">
			    <startEvent id="start"/>
			    <receiveTask id="n_%s" name="arrival"/>
			    <serviceTask id="n_%s" name="TOS lookup" flowable:delegateExpression="${orcaConnectorDelegate}"/>
			    <exclusiveGateway id="route" default="toRefused"/>
			    <serviceTask id="n_%s" name="show OK" flowable:delegateExpression="${orcaDisplayDelegate}"/>
			    <serviceTask id="n_%s" name="tell somebody" flowable:delegateExpression="${orcaNotificationDelegate}"/>
			    <endEvent id="end"/>
			    <endEvent id="refused"/>
			    <sequenceFlow id="f1" sourceRef="start" targetRef="n_%s"/>
			    <sequenceFlow id="f2" sourceRef="n_%s" targetRef="n_%s"/>
			    <sequenceFlow id="f3" sourceRef="n_%s" targetRef="route"/>
			    <sequenceFlow id="toDisplay" sourceRef="route" targetRef="n_%s">
			      <conditionExpression xsi:type="tFormalExpression">${orcaResponseStatus == 200}</conditionExpression>
			    </sequenceFlow>
			    <sequenceFlow id="toRefused" sourceRef="route" targetRef="refused"/>
			    <sequenceFlow id="f4" sourceRef="n_%s" targetRef="n_%s"/>
			    <sequenceFlow id="f5" sourceRef="n_%s" targetRef="end"/>
			  </process>
			</definitions>
			""".formatted(PROCESS_KEY, WAIT, CONN, DISP, NOTE,
			WAIT, WAIT, CONN, CONN, DISP, DISP, NOTE, NOTE).strip();

	@AfterAll
	static void shutDown() {
		if (processEngine != null) {
			processEngine.close();
		}
	}

	@BeforeEach
	void resetTheWorld() {
		connectorRequests.clear();
		edgeCommands.clear();
		notified.clear();
		connectorAnswer = new ConnectorResult(200, Map.of());
		edgeAnswer = EdgeOutcome.SUCCEEDED;
	}

	/** Admission-shape seeding: the row, with its engine correlation, before any signal. */
	private static Visit startVisit() {
		long laneId = LANES.incrementAndGet();
		String externalId = UUID.randomUUID().toString();
		EngineInstanceRef instance = engine.start(PROCESS_KEY, new TenantRef(SITE),
				Map.of("orcaLaneId", laneId));
		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, "
						+ "process_instance_id, workflow_id, definition_version) "
						+ "VALUES (?, ?, ?, 'ACTIVE', ?, 980001, 1)",
				externalId, SITE, laneId, instance.value());
		long executionId = jdbc.queryForObject(
				"SELECT execution_id FROM execution WHERE external_id = ?", Long.class, externalId);
		return new Visit(externalId, executionId, laneId, instance);
	}

	private record Visit(String externalId, long executionId, long laneId, EngineInstanceRef instance) {
	}

	private static <T> T scoped(java.util.function.Supplier<T> work) {
		return ScopeContext.callIn(Scope.of("site_external_id", Set.of(SITE)), work::get);
	}

	@Test
	void aCompiledVisitRunsItsWholeChainAndEveryWriteLands() {
		connectorAnswer = new ConnectorResult(200, Map.of("tos_state", "LOADED"));
		Visit visit = startVisit();
		engine.signal(visit.instance(), "n_" + WAIT, Map.of());

		// The connector was asked with the ORCA identity, never the engine's.
		assertThat(connectorRequests).hasSize(1);
		ConnectorRequest request = connectorRequests.get(0);
		assertThat(request.nodeUuid()).isEqualTo(CONN);
		assertThat(request.name()).isEqualTo("TOS lookup");
		assertThat(request.laneId()).isEqualTo(visit.laneId());
		assertThat(request.visit()).isNotNull();
		assertThat(request.visit().executionId()).isEqualTo(visit.executionId());
		assertThat(request.visit().externalId()).isEqualTo(visit.externalId());

		// The status routed the visit to the display, and the display crossed the edge.
		assertThat(edgeCommands).hasSize(1);
		EdgeCommand display = edgeCommands.get(0);
		assertThat(display.kind()).isEqualTo(EdgeCommand.EdgeKind.DISPLAY);
		assertThat(display.name()).isEqualTo("show OK");
		assertThat(display.idempotencyKey())
				.isEqualTo(visit.instance().value() + ":n_" + DISP);

		// The notification followed, and the instance finished.
		assertThat(notified).containsExactly("n_" + NOTE + "|tell somebody");
		assertThat(engine.stateOf(visit.instance()).state()).isEqualTo(InstanceState.COMPLETED);

		// The extracted dataset is DURABLE on the visit, not only mirrored into variables…
		assertThat(scoped(() -> dataset.value(visit.executionId(), "tos_state"))).contains("LOADED");
		// …and the step's own row carries exactly what this connector answered.
		assertThat(scoped(() -> trace.latestStep(visit.executionId(), CONN)).orElseThrow()
				.executionPayload()).contains("\"tos_state\":\"LOADED\"");
	}

	@Test
	void aNonMatchingStatusTakesTheDefaultFlowAndNoEffectFires() {
		connectorAnswer = new ConnectorResult(503, Map.of());
		Visit visit = startVisit();
		engine.signal(visit.instance(), "n_" + WAIT, Map.of());

		assertThat(connectorRequests).hasSize(1);
		assertThat(edgeCommands).as("a refused visit must not command the display").isEmpty();
		assertThat(notified).isEmpty();
		assertThat(engine.stateOf(visit.instance()).state()).isEqualTo(InstanceState.COMPLETED);
	}

	@Test
	void failedAndUnknownAreDifferentFailuresAtTheEdge() {
		edgeAnswer = EdgeOutcome.FAILED;
		Visit failed = startVisit();
		assertThatThrownBy(() -> engine.signal(failed.instance(), "n_" + WAIT, Map.of()))
				.hasStackTraceContaining("FAILED at the edge");

		edgeAnswer = EdgeOutcome.UNKNOWN;
		Visit unknown = startVisit();
		assertThatThrownBy(() -> engine.signal(unknown.instance(), "n_" + WAIT, Map.of()))
				.hasStackTraceContaining("outcome UNKNOWN")
				// The failure names the one safe way to re-drive it.
				.hasStackTraceContaining(unknown.instance().value() + ":n_" + DISP);
	}

	@Test
	void aVisitWithNoOrcaRowCannotAdvancePastTheWriteBehind() {
		// No execution row is inserted. The step recorder is the write-behind guard:
		// an engine advance this service has no record of must fail the command, not
		// go silent — so the signal dies before the connector is ever asked.
		EngineInstanceRef instance = engine.start(PROCESS_KEY, new TenantRef(SITE),
				Map.of("orcaLaneId", 9999L));
		assertThatThrownBy(() -> engine.signal(instance, "n_" + WAIT, Map.of()))
				.hasStackTraceContaining("no execution row correlates");
		assertThat(connectorRequests).isEmpty();
	}
}
