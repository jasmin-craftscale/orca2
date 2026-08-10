package com.lynxis.orca.runtime.execution.engine.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lynxis.orca.runtime.execution.engine.DeployableDefinition;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngine;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngineContract;
import com.lynxis.orca.runtime.execution.engine.WorkflowEngineException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The fake's run of the one contract test. The Flowable adapter runs it too, unchanged. */
class InMemoryWorkflowEngineContractTest extends WorkflowEngineContract {

    @Override
    protected WorkflowEngine engine() {
        return new InMemoryWorkflowEngine();
    }

    @Override
    protected DeployableDefinition definitionRunningStraightThrough(String definitionKey) {
        return FakeDefinition.of(definitionKey,
                FakeDefinition.Step.automatic("start"),
                FakeDefinition.Step.automatic("terminator"));
    }

    @Override
    protected DeployableDefinition definitionWaitingAt(String definitionKey, String waitPointId) {
        return FakeDefinition.of(definitionKey,
                FakeDefinition.Step.automatic("start"),
                FakeDefinition.Step.waitPoint(waitPointId),
                FakeDefinition.Step.automatic("terminator"));
    }

    @Test
    void rejectsForeignDefinitionShapes() {
        InMemoryWorkflowEngine engine = new InMemoryWorkflowEngine();
        DeployableDefinition foreign = () -> "proc_foreign";

        assertThatExceptionOfType(WorkflowEngineException.class)
                .isThrownBy(() -> engine.deploy(foreign, SITE_A))
                .withMessageContaining("FakeDefinition");
    }

    @Test
    void exposesTheDeployedVersionForAssertions() {
        InMemoryWorkflowEngine engine = new InMemoryWorkflowEngine();

        assertThat(engine.deployedVersion("proc_x")).isEmpty();
        engine.deploy(definitionRunningStraightThrough("proc_x"), SITE_A);
        assertThat(engine.deployedVersion("proc_x")).contains(1);
    }
}
