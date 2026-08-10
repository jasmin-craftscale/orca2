package com.lynxis.orca.runtime.execution.engine.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lynxis.orca.runtime.execution.engine.BpmnDefinition;
import com.lynxis.orca.runtime.execution.engine.DeployableDefinition;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngine;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngineContract;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngineException;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Flowable adapter's run of the one contract test (the highest-leverage red, now
 * green) — <em>unchanged</em> from the fake's run, or the seam has leaked (D10).
 *
 * <p>Doubles as the vendored-DDL proof: the schema here is built exclusively
 * by Flyway (V001–V003, including the vendored Flowable 8.0.0 DDL) and the engine boots
 * with {@code database-schema-update=false} — if the vendored script were incomplete or
 * mis-qualified, the engine would refuse to start or fail on first use.
 */
class FlowableWorkflowEngineContractTest extends WorkflowEngineContract {

    private static ProcessEngine processEngine;

    /** The service login's identity: its default schema IS the service schema. */
    private static final String ENGINE_LOGIN = "orca_runtime_contract";
    private static final String ENGINE_PASSWORD = "Fl0wable!Contract2026";

    @BeforeAll
    static void bootEngineOnFlywayBuiltSchema() {
        processEngine = FlowableTestEngines.boot(
                "flowable_contract", ENGINE_LOGIN, ENGINE_PASSWORD, 8);
    }

    @AfterAll
    static void shutDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Override
    protected WorkflowEngine engine() {
        return new FlowableWorkflowEngine(processEngine);
    }

    @Override
    protected DeployableDefinition definitionRunningStraightThrough(String definitionKey) {
        return new BpmnDefinition(definitionKey, bpmn(definitionKey, """
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                """));
    }

    @Override
    protected DeployableDefinition definitionWaitingAt(String definitionKey, String waitPointId) {
        return new BpmnDefinition(definitionKey, bpmn(definitionKey, """
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="%1$s"/>
                    <receiveTask id="%1$s" name="%1$s"/>
                    <sequenceFlow id="f2" sourceRef="%1$s" targetRef="end"/>
                    <endEvent id="end"/>
                """.formatted(waitPointId)));
    }

    private static byte[] bpmn(String processId, String body) {
        return FlowableTestEngines.bpmn(processId, body);
    }

    @Test
    void rejectsForeignDefinitionShapes() {
        DeployableDefinition foreign = () -> "proc_foreign";

        assertThatExceptionOfType(WorkflowEngineException.class)
                .isThrownBy(() -> engine().deploy(foreign, SITE_A))
                .withMessageContaining("BpmnDefinition");
    }

    /** The over-long-key regression: a typed failure, not a truncated id. */
    @Test
    void anOverLongDefinitionKeyIsATypedFailure() {
        String overLong = "proc_" + "9".repeat(60);

        assertThatExceptionOfType(WorkflowEngineException.class)
                .isThrownBy(() -> engine().deploy(definitionRunningStraightThrough(overLong), SITE_A))
                .withMessageContaining("C2");
    }

    /** P2: a key that does not match the BPMN process id deploys something nobody can start — fail loud. */
    @Test
    void aKeyProcessIdMismatchIsATypedFailure() {
        DeployableDefinition mismatched = new BpmnDefinition("proc_key_a", bpmn("proc_key_b", """
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                """));

        assertThatExceptionOfType(WorkflowEngineException.class)
                .isThrownBy(() -> engine().deploy(mismatched, SITE_A))
                .withMessageContaining("compiler must emit them identically");
    }

    /** The vendored DDL carries the exact schema version the engine expects. */
    @Test
    void vendoredSchemaVersionMatchesTheEngine() {
        String version = processEngine.getManagementService()
                .getProperties()
                .get("schema.version");

        assertThat(version).isEqualTo("8.0.0.0");
    }
}
