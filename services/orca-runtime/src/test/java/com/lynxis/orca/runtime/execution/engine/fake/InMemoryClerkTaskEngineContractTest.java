package com.lynxis.orca.runtime.execution.engine.fake;

import com.lynxis.orca.runtime.execution.engine.ClerkTaskEngineContract;
import com.lynxis.orca.runtime.execution.engine.DeployableDefinition;

/** The fake's run of the clerk-task contract — no database, no engine boot, milliseconds. */
class InMemoryClerkTaskEngineContractTest extends ClerkTaskEngineContract {

    @Override
    protected Engine fresh() {
        InMemoryWorkflowEngine engine = new InMemoryWorkflowEngine();
        return new Engine(engine, engine);
    }

    @Override
    protected DeployableDefinition definitionWithClerkStep(String definitionKey, String nodeId) {
        return FakeDefinition.of(definitionKey,
                FakeDefinition.Step.automatic("start"),
                FakeDefinition.Step.clerkWaitPoint(nodeId),
                FakeDefinition.Step.automatic("end"));
    }

    @Override
    protected DeployableDefinition definitionWaitingAt(String definitionKey, String waitPointId) {
        return FakeDefinition.of(definitionKey,
                FakeDefinition.Step.automatic("start"),
                FakeDefinition.Step.waitPoint(waitPointId),
                FakeDefinition.Step.automatic("end"));
    }
}
