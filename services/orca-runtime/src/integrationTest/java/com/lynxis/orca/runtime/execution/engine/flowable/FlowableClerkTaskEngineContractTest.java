package com.lynxis.orca.runtime.execution.engine.flowable;

import com.lynxis.orca.runtime.execution.engine.BpmnDefinition;
import com.lynxis.orca.runtime.execution.engine.ClerkTaskEngineContract;
import com.lynxis.orca.runtime.execution.engine.DeployableDefinition;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The Flowable adapter's run of the clerk-task contract — <em>unchanged</em> from the fake's,
 * or the queue has grown a dependency on the engine (D10).
 *
 * <p>The clerk step here is a bare {@code userTask} with no assignee and no candidate group,
 * which is exactly what the compiler emits for MANUAL_INPUT: routing is ORCA's (teams × screen × lane),
 * and duplicating it into engine identity links would give the grid a second answer to
 * disagree with.
 */
class FlowableClerkTaskEngineContractTest extends ClerkTaskEngineContract {

    private static ProcessEngine processEngine;

    @BeforeAll
    static void bootEngine() {
        processEngine = FlowableTestEngines.boot(
                "flowable_clerk", "orca_runtime_clerk", "Fl0wable!Clerk2026", 8);
    }

    @AfterAll
    static void shutDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Override
    protected Engine fresh() {
        FlowableWorkflowEngine engine = new FlowableWorkflowEngine(processEngine);
        return new Engine(engine, engine);
    }

    @Override
    protected DeployableDefinition definitionWithClerkStep(String definitionKey, String nodeId) {
        return new BpmnDefinition(definitionKey, FlowableTestEngines.bpmn(definitionKey, """
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="%1$s"/>
                    <userTask id="%1$s" name="%1$s"/>
                    <sequenceFlow id="f2" sourceRef="%1$s" targetRef="end"/>
                    <endEvent id="end"/>
                """.formatted(nodeId)));
    }

    @Override
    protected DeployableDefinition definitionWaitingAt(String definitionKey, String waitPointId) {
        return new BpmnDefinition(definitionKey, FlowableTestEngines.bpmn(definitionKey, """
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="%1$s"/>
                    <receiveTask id="%1$s" name="%1$s"/>
                    <sequenceFlow id="f2" sourceRef="%1$s" targetRef="end"/>
                    <endEvent id="end"/>
                """.formatted(waitPointId)));
    }
}
