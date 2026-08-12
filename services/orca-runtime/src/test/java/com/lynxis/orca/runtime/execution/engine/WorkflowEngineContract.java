package com.lynxis.orca.runtime.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The highest-leverage test in the module</b>: one contract, two
 * implementations. Written against {@code InMemoryWorkflowEngine} before Flowable exists;
 * The Flowable adapter re-runs it <em>unchanged</em>. If the adapter needs this
 * class modified to pass, the seam has leaked and D10's reversibility is nominal.
 *
 * <p>Subclasses supply only the engine and definitions of two canonical shapes — nothing
 * about assertions is theirs to change.
 */
public abstract class WorkflowEngineContract {

    protected static final TenantRef SITE_A = new TenantRef("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /** A fresh engine per test. */
    protected abstract WorkflowEngine engine();

    /** A definition that runs from start to completion with no wait. */
    protected abstract DeployableDefinition definitionRunningStraightThrough(String definitionKey);

    /** A definition that parks at exactly one wait point with the given id, then completes. */
    protected abstract DeployableDefinition definitionWaitingAt(String definitionKey, String waitPointId);

    @Test
    void deploymentVersionsStartAtOneAndIncrementPerKey() {
        WorkflowEngine engine = engine();

        EngineDeployment first = engine.deploy(definitionRunningStraightThrough("proc_31675"), SITE_A);
        EngineDeployment second = engine.deploy(definitionRunningStraightThrough("proc_31675"), SITE_A);
        EngineDeployment other = engine.deploy(definitionRunningStraightThrough("proc_99999"), SITE_A);

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(other.version()).isEqualTo(1);
    }

    @Test
    void startingAnUndeployedDefinitionIsATypedFailure() {
        WorkflowEngine engine = engine();

        assertThatExceptionOfType(UnknownDefinitionException.class)
                .isThrownBy(() -> engine.start("proc_missing", SITE_A, Map.of()));
    }

    @Test
    void aStraightThroughDefinitionRunsToCompletion() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionRunningStraightThrough("proc_1"), SITE_A);

        EngineInstanceRef instance = engine.start("proc_1", SITE_A, Map.of());

        InstanceSnapshot snapshot = engine.stateOf(instance);
        assertThat(snapshot.state()).isEqualTo(InstanceState.COMPLETED);
        assertThat(snapshot.waitPoint()).isEmpty();
    }

    @Test
    void anInstanceParksAtItsWaitPoint() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionWaitingAt("proc_2", "wait_scan"), SITE_A);

        EngineInstanceRef instance = engine.start("proc_2", SITE_A, Map.of());

        InstanceSnapshot snapshot = engine.stateOf(instance);
        assertThat(snapshot.state()).isEqualTo(InstanceState.WAITING);
        assertThat(snapshot.waitPoint()).contains("wait_scan");
    }

    @Test
    void signallingTheWaitPointResumesToCompletion() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionWaitingAt("proc_3", "wait_scan"), SITE_A);
        EngineInstanceRef instance = engine.start("proc_3", SITE_A, Map.of());

        engine.signal(instance, "wait_scan", Map.of("plate", "A-123"));

        assertThat(engine.stateOf(instance).state()).isEqualTo(InstanceState.COMPLETED);
    }

    @Test
    void signallingTheWrongWaitPointIsATypedFailureAndDoesNotAdvance() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionWaitingAt("proc_4", "wait_scan"), SITE_A);
        EngineInstanceRef instance = engine.start("proc_4", SITE_A, Map.of());

        assertThatExceptionOfType(InvalidSignalException.class)
                .isThrownBy(() -> engine.signal(instance, "wait_other", Map.of()));

        assertThat(engine.stateOf(instance).state()).isEqualTo(InstanceState.WAITING);
        assertThat(engine.stateOf(instance).waitPoint()).contains("wait_scan");
    }

    @Test
    void signallingAFinishedInstanceIsATypedFailure() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionRunningStraightThrough("proc_5"), SITE_A);
        EngineInstanceRef instance = engine.start("proc_5", SITE_A, Map.of());

        assertThatExceptionOfType(InvalidSignalException.class)
                .isThrownBy(() -> engine.signal(instance, "anything", Map.of()));
    }

    @Test
    void cancellingAWaitingInstanceStopsIt() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionWaitingAt("proc_6", "wait_scan"), SITE_A);
        EngineInstanceRef instance = engine.start("proc_6", SITE_A, Map.of());

        engine.cancel(instance, "lane reset");

        assertThat(engine.stateOf(instance).state()).isEqualTo(InstanceState.CANCELLED);
    }

    @Test
    void cancellingAFinishedInstanceIsATypedFailure() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionRunningStraightThrough("proc_7"), SITE_A);
        EngineInstanceRef instance = engine.start("proc_7", SITE_A, Map.of());

        assertThatExceptionOfType(InvalidSignalException.class)
                .isThrownBy(() -> engine.cancel(instance, "too late"));
    }

    @Test
    void askingForAnUnknownInstanceIsATypedFailure() {
        WorkflowEngine engine = engine();

        assertThatExceptionOfType(UnknownInstanceException.class)
                .isThrownBy(() -> engine.stateOf(new EngineInstanceRef("nope")));
    }

    @Test
    void anInstanceCarriesItsTenant() {
        WorkflowEngine engine = engine();
        engine.deploy(definitionWaitingAt("proc_8", "wait_scan"), SITE_A);

        EngineInstanceRef instance = engine.start("proc_8", SITE_A, Map.of());

        assertThat(engine.stateOf(instance).tenant()).isEqualTo(SITE_A);
    }
}
