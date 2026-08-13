package com.lynxis.orca.runtime.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The clerk-work contract: one specification, two implementations. Written the same way
 * {@code WorkflowEngineContract} was — if the Flowable run needs this class modified to pass,
 * the seam has leaked and the queue is bound to the engine rather than to ORCA's vocabulary.
 *
 * <p>What it pins down is the lifecycle mapping the plan calls for: <b>unassigned IS
 * queued</b>, claim is the only transition that can fail on contention, park and takeover
 * cannot, and completing the item advances the visit. Nothing here mentions a status column,
 * because on the engine side there is none to drift.
 */
public abstract class ClerkTaskEngineContract {

    protected static final TenantRef SITE_A = new TenantRef("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /**
     * The two seams onto ONE engine. A record rather than two accessors so a subclass cannot
     * hand back two different objects — the fake builds a new engine per test, and a queue
     * pointing at a different engine's visits would pass everything while proving nothing.
     */
    public record Engine(WorkflowEngine flow, ClerkTaskEngine clerk) {
    }

    /** A fresh engine per test, seen through both seams. */
    protected abstract Engine fresh();

    /** A definition that parks at one clerk-resolved wait point, then completes. */
    protected abstract DeployableDefinition definitionWithClerkStep(String definitionKey, String nodeId);

    /** A definition that parks at one wait point no clerk resolves (a device wait). */
    protected abstract DeployableDefinition definitionWaitingAt(String definitionKey, String waitPointId);

    @Test
    void aParkedClerkStepIsExactlyOneQueuedItem() {
        Engine engine = fresh();
        engine.flow().deploy(definitionWithClerkStep("proc_c1", "n_review"), SITE_A);

        EngineInstanceRef visit = engine.flow().start("proc_c1", SITE_A, Map.of());

        List<ClerkTask> open = engine.clerk().openTasks(visit);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).nodeId()).isEqualTo("n_review");
        assertThat(open.get(0).instance()).isEqualTo(visit);
        assertThat(open.get(0).assignedTo()).as("unassigned is queued").isEmpty();
    }

    @Test
    void aVisitWaitingOnADeviceHasNoClerkWork() {
        Engine engine = fresh();
        engine.flow().deploy(definitionWaitingAt("proc_c2", "wait_scan"), SITE_A);

        EngineInstanceRef visit = engine.flow().start("proc_c2", SITE_A, Map.of());

        assertThat(engine.clerk().openTasks(visit)).isEmpty();
    }

    @Test
    void takingAQueuedItemAssignsIt() {
        Engine engine = fresh();
        String taskId = oneQueuedItem(engine, "proc_c3");

        engine.clerk().claim(taskId, "clerk-anna");

        assertThat(engine.clerk().findTask(taskId).orElseThrow().assignedTo()).contains("clerk-anna");
    }

    @Test
    void takingAHeldItemIsATypedConflictAndTheHolderIsUnchanged() {
        Engine engine = fresh();
        String taskId = oneQueuedItem(engine, "proc_c4");
        engine.clerk().claim(taskId, "clerk-anna");

        assertThatExceptionOfType(TaskAlreadyClaimedException.class)
                .isThrownBy(() -> engine.clerk().claim(taskId, "clerk-bo"));

        assertThat(engine.clerk().findTask(taskId).orElseThrow().assignedTo()).contains("clerk-anna");
    }

    @Test
    void parkingPutsItBackOnTheQueueAndIsIdempotent() {
        Engine engine = fresh();
        String taskId = oneQueuedItem(engine, "proc_c5");
        engine.clerk().claim(taskId, "clerk-anna");

        engine.clerk().release(taskId);
        engine.clerk().release(taskId);

        assertThat(engine.clerk().findTask(taskId).orElseThrow().assignedTo()).isEmpty();
    }

    @Test
    void aParkedItemCanBeTakenAgain() {
        Engine engine = fresh();
        String taskId = oneQueuedItem(engine, "proc_c6");
        engine.clerk().claim(taskId, "clerk-anna");
        engine.clerk().release(taskId);

        engine.clerk().claim(taskId, "clerk-bo");

        assertThat(engine.clerk().findTask(taskId).orElseThrow().assignedTo()).contains("clerk-bo");
    }

    @Test
    void takeoverMovesAHeldItemWithoutAsking() {
        Engine engine = fresh();
        String taskId = oneQueuedItem(engine, "proc_c7");
        engine.clerk().claim(taskId, "clerk-anna");

        engine.clerk().reassign(taskId, "supervisor-cy");

        assertThat(engine.clerk().findTask(taskId).orElseThrow().assignedTo()).contains("supervisor-cy");
    }

    @Test
    void completingTheItemAdvancesTheVisitAndClosesTheTask() {
        Engine engine = fresh();
        engine.flow().deploy(definitionWithClerkStep("proc_c8", "n_review"), SITE_A);
        EngineInstanceRef visit = engine.flow().start("proc_c8", SITE_A, Map.of());
        String taskId = engine.clerk().openTasks(visit).get(0).taskId();
        engine.clerk().claim(taskId, "clerk-anna");

        engine.clerk().complete(taskId, Map.of());

        assertThat(engine.flow().stateOf(visit).state()).isEqualTo(InstanceState.COMPLETED);
        assertThat(engine.clerk().findTask(taskId)).as("a finished item is not open work").isEmpty();
        assertThat(engine.clerk().openTasks(visit)).isEmpty();
    }

    @Test
    void actingOnAnItemSomebodyAlreadyFinishedIsATypedFailure() {
        Engine engine = fresh();
        String taskId = oneQueuedItem(engine, "proc_c9");
        engine.clerk().complete(taskId, Map.of());

        assertThatExceptionOfType(UnknownTaskException.class)
                .isThrownBy(() -> engine.clerk().claim(taskId, "clerk-bo"));
        assertThatExceptionOfType(UnknownTaskException.class)
                .isThrownBy(() -> engine.clerk().release(taskId));
        assertThatExceptionOfType(UnknownTaskException.class)
                .isThrownBy(() -> engine.clerk().reassign(taskId, "clerk-bo"));
        assertThatExceptionOfType(UnknownTaskException.class)
                .isThrownBy(() -> engine.clerk().complete(taskId, Map.of()));
    }

    @Test
    void cancellingAVisitClosesItsClerkWork() {
        Engine engine = fresh();
        engine.flow().deploy(definitionWithClerkStep("proc_c10", "n_review"), SITE_A);
        EngineInstanceRef visit = engine.flow().start("proc_c10", SITE_A, Map.of());
        String taskId = engine.clerk().openTasks(visit).get(0).taskId();

        engine.flow().cancel(visit, "lane reset");

        assertThat(engine.clerk().findTask(taskId)).isEmpty();
    }

    private String oneQueuedItem(Engine engine, String definitionKey) {
        engine.flow().deploy(definitionWithClerkStep(definitionKey, "n_review"), SITE_A);
        EngineInstanceRef visit = engine.flow().start(definitionKey, SITE_A, Map.of());
        return engine.clerk().openTasks(visit).get(0).taskId();
    }
}
