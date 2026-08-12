package com.lynxis.orca.runtime.execution.engine;

import java.util.Map;

/**
 * The reversibility seam: the one interface behind which the workflow engine lives.
 *
 * <p><b>No Flowable type may appear in any signature</b> — ArchUnit rule 6 enforces it.
 * This interface is what makes the engine choice reversible by re-implementing one class,
 * and — the reason it is worth building even if reversal never happens — it lets the
 * compiler and every delegate be tested against {@code InMemoryWorkflowEngine} with no
 * database and no engine boot.
 *
 * <p>The contract is specified by {@code WorkflowEngineContract}: one contract test, two
 * implementations. If the Flowable adapter needs the contract test modified to pass,
 * the seam has leaked and reversibility is gone.
 */
public interface WorkflowEngine {

    /**
     * Deploys a definition. Versioning is per definition key, starting at 1 —
     * never keyed on an ORCA workflow name (names collide).
     */
    EngineDeployment deploy(DeployableDefinition definition, TenantRef tenant);

    /**
     * Starts an instance of the latest deployed version of {@code definitionKey} for
     * {@code tenant}, and runs it until it completes or parks at a wait point.
     *
     * @throws UnknownDefinitionException when no such definition is deployed
     */
    EngineInstanceRef start(String definitionKey, TenantRef tenant, Map<String, Object> correlationKeys);

    /**
     * Delivers the real-world event a parked instance is waiting for, resuming it until
     * the next wait point or completion.
     *
     * @throws UnknownInstanceException when the instance does not exist
     * @throws InvalidSignalException   when the instance is not waiting at {@code waitPointId}
     */
    void signal(EngineInstanceRef instance, String waitPointId, Map<String, Object> payload);

    /**
     * Terminates a running or waiting instance.
     *
     * @throws UnknownInstanceException when the instance does not exist
     * @throws InvalidSignalException   when the instance already finished
     */
    void cancel(EngineInstanceRef instance, String reason);

    /**
     * @throws UnknownInstanceException when the instance does not exist
     */
    InstanceSnapshot stateOf(EngineInstanceRef instance);
}
