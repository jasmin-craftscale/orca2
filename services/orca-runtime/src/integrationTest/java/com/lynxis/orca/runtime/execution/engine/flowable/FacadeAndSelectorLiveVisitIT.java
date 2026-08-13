package com.lynxis.orca.runtime.execution.engine.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.lynxis.orca.platform.scope.JdbcScopeSeam;
import com.lynxis.orca.platform.scope.Scope;
import com.lynxis.orca.platform.scope.ScopeContext;
import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.execution.api.ExecutionFacade;
import com.lynxis.orca.runtime.execution.api.UnresolvableSelectorException;
import com.lynxis.orca.runtime.execution.engine.BpmnDefinition;
import com.lynxis.orca.runtime.execution.engine.EngineInstanceRef;
import com.lynxis.orca.runtime.execution.engine.InstanceState;
import com.lynxis.orca.runtime.execution.engine.TenantRef;
import com.lynxis.orca.runtime.execution.engine.UnknownInstanceException;
import com.lynxis.orca.runtime.execution.internal.DefinitionRegistry;
import com.lynxis.orca.runtime.execution.internal.RuntimeExecutionFacade;
import com.lynxis.orca.runtime.execution.internal.VisitDataWriter;
import com.lynxis.orca.runtime.execution.internal.selector.RuntimeSelectorDataProvider;
import com.lynxis.orca.runtime.execution.internal.selector.SiteCatalog;
import com.lynxis.orca.runtime.execution.persistence.AdmissionRepository;
import com.lynxis.orca.runtime.execution.persistence.NodeExecutionTraceRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitDatasetRepository;
import com.lynxis.orca.runtime.execution.persistence.VisitTreeRepository;

/**
 * The facade and the selector provider against a LIVE visit — a real engine on a
 * real Flyway-built schema, with the step recorder listening. The loop this
 * proves is the one every outbound connector body will run through: a signal
 * writes its dataset durably BEFORE the engine advances, the recorder lands the
 * step's payload on the trace in the same command, and the provider answers the
 * three questions the estate actually asks — the visit's dataset, a step's
 * payload, the visit's own columns — plus the sibling lookup all cross-workflow
 * selectors run through.
 *
 * <p>Visits are seeded admission-shape: the row exists with its engine
 * correlation before anything signals it. The definition's start event is
 * deliberately NOT an ORCA node ({@code n_} prefix): steps that complete inside
 * the engine-start command would find no correlated row yet — admission records
 * the instance id after the start returns — and that ordering question belongs
 * to the phase that admits compiled processes, not to this suite.
 */
class FacadeAndSelectorLiveVisitIT {

	private static final String SITE = "SITE-FACADE-IT";
	private static final String WORKFLOW_UUID = "1e1e1e1e-1e1e-1e1e-1e1e-1e1e1e1e1e1e";
	private static final String SCAN_NODE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
	private static final String PROCESS_KEY = "proc_970001";

	private static ProcessEngine processEngine;
	private static JdbcTemplate jdbc;
	private static com.lynxis.orca.runtime.execution.engine.WorkflowEngine engine;
	private static NodeExecutionTraceRepository trace;
	private static VisitDatasetRepository dataset;
	private static VisitTreeRepository visitTree;
	private static DefinitionRegistry definitions;
	private static ExecutionFacade facade;
	private static RuntimeSelectorDataProvider provider;

	private static final AtomicLong LANES = new AtomicLong(9100);

	@BeforeAll
	static void bootEverythingOnOneDatabase() {
		FlowableTestEngines.BootedEngine booted = FlowableTestEngines.bootWithDatabase(
				"facade_selector", "orca_runtime_facade", "Facade!Selector2026", 8);
		processEngine = booted.engine();
		jdbc = new JdbcTemplate(new DriverManagerDataSource(
				booted.db().jdbcUrl(), booted.db().username(), booted.db().password()));

		ScopeSeam seam = new JdbcScopeSeam(jdbc);
		trace = new NodeExecutionTraceRepository(seam);
		dataset = new VisitDatasetRepository(seam);
		visitTree = new VisitTreeRepository(seam);
		AdmissionRepository admission = new AdmissionRepository(seam);

		// The recorder listens exactly as production wiring attaches it.
		processEngine.getRuntimeService()
				.addEventListener(new NodeExecutionRecorder(trace, SITE));

		engine = new FlowableWorkflowEngine(processEngine);
		definitions = new DefinitionRegistry();
		VisitDataWriter visitData = new VisitDataWriter(dataset, SITE);
		facade = new RuntimeExecutionFacade(engine, admission, visitData, SITE);
		provider = new RuntimeSelectorDataProvider(visitTree, trace, dataset,
				definitions, SiteCatalog.UNBOUND, SITE);

		engine.deploy(new BpmnDefinition(PROCESS_KEY, FlowableTestEngines.bpmn(PROCESS_KEY, """
				    <startEvent id="start"/>
				    <receiveTask id="n_%s" name="the scan"/>
				    <endEvent id="end"/>
				    <sequenceFlow id="f1" sourceRef="start" targetRef="n_%s"/>
				    <sequenceFlow id="f2" sourceRef="n_%s" targetRef="end"/>
				""".formatted(SCAN_NODE, SCAN_NODE, SCAN_NODE))), new TenantRef(SITE));
		definitions.register(WORKFLOW_UUID, PROCESS_KEY);
	}

	@AfterAll
	static void shutDown() {
		if (processEngine != null) {
			processEngine.close();
		}
	}

	/** Admission-shape seeding: the row, with its engine correlation, before any signal. */
	private static Visit startVisit() {
		long laneId = LANES.incrementAndGet();
		String externalId = UUID.randomUUID().toString();
		EngineInstanceRef instance = engine.start(PROCESS_KEY, new TenantRef(SITE), Map.of());
		jdbc.update("INSERT INTO execution (external_id, site_external_id, lane_id, status, "
						+ "process_instance_id, workflow_id, definition_version) "
						+ "VALUES (?, ?, ?, 'ACTIVE', ?, 970001, 1)",
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
	void aSignalWritesDurablyLandsTheStepAndAdvancesTheVisit() {
		Visit visit = startVisit();
		assertThat(facade.waitingNodeOf(visit.externalId()))
				.as("parked at the scan, answered without the compiler's prefix")
				.contains(SCAN_NODE);

		facade.signalExecution(visit.externalId(), SCAN_NODE,
				Map.of("rfid", "E20034120", "weight", "31400", ExecutionFacade.OUTCOME_KEY, "ignored-here"));

		// Durable: the dataset holds the payload's dataset half — never the routing outcome.
		assertThat(scoped(() -> dataset.value(visit.executionId(), "rfid"))).contains("E20034120");
		assertThat(scoped(() -> dataset.value(visit.executionId(), "outcome"))).isEmpty();
		// Traced: the recorder landed the step, carrying what the signal offered.
		NodeExecutionTraceRepository.StepRow step =
				scoped(() -> trace.latestStep(visit.executionId(), SCAN_NODE)).orElseThrow();
		assertThat(step.executionPayload()).contains("\"rfid\":\"E20034120\"");
		// Advanced: nothing waits any more, and the engine reports the instance done.
		assertThat(facade.waitingNodeOf(visit.externalId())).isEmpty();
		assertThat(engine.stateOf(visit.instance()).state()).isEqualTo(InstanceState.COMPLETED);
	}

	@Test
	void theProviderAnswersTheThreeQuestionsTheEstateAsksOfALiveVisit() throws Exception {
		Visit visit = startVisit();
		facade.signalExecution(visit.externalId(), SCAN_NODE, Map.of("plate", "A-123"));
		int visitId = (int) visit.executionId();

		// $.workflow.dataset.<key> — the largest family.
		assertThat(provider.fetchDataSets("plate", visitId)).isEqualTo("A-123");
		assertThat(provider.fetchDataSets("nothing_wrote_this", visitId))
				.as("a missing key is an empty string, exactly as the platform answers")
				.isEqualTo("");

		// $.<nodeUuid>.dataset.… — what the step itself carried.
		Map<String, Object> step = provider.fetchNodeExecution(970001, SCAN_NODE, visitId);
		assertThat(step).isNotEmpty();
		assertThat((String) step.get("ExecutionPayload")).contains("\"plate\":\"A-123\"");

		// $.<uuid>.workflowExecution.<column> — the smallest family.
		assertThat(provider.fetchWorkflowExecutionData("lane_id", visitId)).isEqualTo(visit.laneId());
		assertThat(provider.fetchWorkflowExecutionData("execution_uuid", visitId))
				.isEqualTo(visit.externalId());
		assertThat(provider.fetchWorkflowExecutionData("workflow_id", visitId)).isEqualTo(970001L);
		// A column that is not on the visit row cannot ever hold a value: authoring
		// error, not missing data — the visit stops instead of branching on an empty read.
		assertThatExceptionOfType(UnresolvableSelectorException.class)
				.isThrownBy(() -> provider.fetchWorkflowExecutionData("no_such_column", visitId))
				.withMessageContaining("not a column of the visit");

		// The evaluator's own uuid-to-id conversion is answered from the runtime's
		// tables, not sent to the unbound catalog…
		assertThat(provider.getPrimaryKeyByUuid("workflow_executions",
				"workflow_execution_id", "workflow_execution_uuid", visit.externalId()))
				.isEqualTo(visitId);
		// …and a token that is not a uuid is the evaluator's alias fall-through, not an error.
		assertThat(provider.getPrimaryKeyByUuid("workflow_executions",
				"workflow_execution_id", "workflow_execution_uuid", "an-alias-name")).isZero();
		// The cross-workflow family's first hop reads what is deployed.
		assertThat(provider.getPrimaryKeyByUuid("workflow",
				"workflow_id", "workflow_uuid", WORKFLOW_UUID)).isEqualTo(970001);
	}

	/**
	 * The correction the platform's misnamed method demands. Its SQL resolves the
	 * execution of a NAMED WORKFLOW within the SAME VISIT — the sibling lookup all
	 * cross-workflow selectors run through — not anything "previous". Implementing
	 * the name instead of the query would answer empty for every one of them and
	 * look like it worked.
	 */
	@Test
	void theSameVisitSiblingLookupResolvesAWorkflowInThisVisitNotAnotherTruck() {
		Visit visit = startVisit();
		Visit stranger = startVisit();
		long childId = scoped(() -> trace.insertChildExecution(UUID.randomUUID().toString(),
				SITE, visit.laneId(), visit.executionId(), 970002L, 1, "engine-child-" + visit.laneId()));

		// The member of THIS visit running that workflow — found from the root and
		// from the child alike, never the stranger on another lane.
		assertThat(provider.getPreviousWorkflowExecutionId(970002, (int) visit.executionId()))
				.isEqualTo((int) childId);
		assertThat(provider.getPreviousWorkflowExecutionId(970001, (int) childId))
				.isEqualTo((int) visit.executionId())
				.isNotEqualTo((int) stranger.executionId());
		// A workflow this visit never ran resolves to nothing, not to a stranger.
		assertThat(provider.getPreviousWorkflowExecutionId(123456, (int) visit.executionId())).isZero();
		// And the child's visit identity is its root.
		assertThat(provider.fetchWorkflowExecutionData("visit_id", (int) childId))
				.isEqualTo(visit.executionId());
	}

	@Test
	void theFacadeBeginsWhereAdmissionEnds() {
		Visit visit = startVisit();
		assertThat(facade.runningExecutionOnLane(visit.laneId())).contains(visit.externalId());
		assertThat(facade.runningExecutionOnLane(86000L)).isEmpty();

		assertThatExceptionOfType(UnknownInstanceException.class)
				.isThrownBy(() -> facade.signalExecution(
						UUID.randomUUID().toString(), SCAN_NODE, Map.of()));
	}

	@Test
	void aCancelTerminatesTheEngineInstance() {
		Visit visit = startVisit();
		facade.cancelExecution(visit.externalId(), "lane reset");
		assertThat(engine.stateOf(visit.instance()).state()).isEqualTo(InstanceState.CANCELLED);
		assertThat(facade.waitingNodeOf(visit.externalId())).isEmpty();
	}

	@Test
	void aQuestionThisRuntimeCannotAnswerYetFailsByNameRatherThanEmpty() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> provider.getResourceConfigurations(1, "LANE", "anything"))
				.withMessageContaining("SiteCatalog.resourceConfiguration");
		// And the reference-data escape hatch stays shut until somebody rules on a safe shape.
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> provider.executeQuery("SELECT 1"))
				.withMessageContaining("unparameterised");
	}
}
