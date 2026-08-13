package com.lynxis.orca.runtime.execution.engine.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynxis.orca.runtime.execution.engine.BpmnDefinition;
import com.lynxis.orca.runtime.execution.engine.ClerkTask;
import com.lynxis.orca.runtime.execution.engine.EngineInstanceRef;
import com.lynxis.orca.runtime.execution.engine.TaskAlreadyClaimedException;
import com.lynxis.orca.runtime.execution.engine.TenantRef;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The claim-race test — the one that decides whether moving the claim onto the engine was
 * worth doing. A thousand clerks press "take" on the same item at the same instant.
 *
 * <p><b>Exactly one wins, and every loser is told so in the same typed way.</b> The Go
 * implementation gets there with a hand-rolled conditional UPDATE
 * ({@code WHERE status IN ('QUEUED','ESCALATED_TO_LANE')}) whose correctness depends on that
 * WHERE clause staying right through every future status added to the enum; here it is a
 * property of the task row's version, which nobody can forget to maintain.
 *
 * <p>Honest about the machinery: the thousand attempts run across
 * {@value #RACING_THREADS} platform threads released together by one latch, because a
 * thousand live JDBC connections would measure the connection pool rather than the claim.
 * Contention is real either way — the assertion is on the outcome, not the thread count.
 */
class ConcurrentClaimIntegrationTest {

    private static final int CLAIM_ATTEMPTS = 1_000;
    private static final int RACING_THREADS = 16;

    private static ProcessEngine processEngine;

    @BeforeAll
    static void bootEngine() {
        processEngine = FlowableTestEngines.boot(
                "flowable_claim_race", "orca_runtime_race", "Fl0wable!Race2026", RACING_THREADS + 4);
    }

    @AfterAll
    static void shutDown() {
        if (processEngine != null) {
            processEngine.close();
        }
    }

    @Test
    void aThousandRacingClaimsProduceExactlyOneVictor() throws Exception {
        FlowableWorkflowEngine engine = new FlowableWorkflowEngine(processEngine);
        engine.deploy(new BpmnDefinition("proc_claim_race", FlowableTestEngines.bpmn("proc_claim_race", """
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="n_review"/>
                    <userTask id="n_review" name="review"/>
                    <sequenceFlow id="f2" sourceRef="n_review" targetRef="end"/>
                    <endEvent id="end"/>
                """)), SITE_A);
        EngineInstanceRef visit = engine.start("proc_claim_race", SITE_A, Map.of());
        String taskId = engine.openTasks(visit).get(0).taskId();

        AtomicInteger won = new AtomicInteger();
        AtomicInteger lostTyped = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService clerks = Executors.newFixedThreadPool(RACING_THREADS);
        try {
            List<Callable<RuntimeException>> attempts = java.util.stream.IntStream.range(0, CLAIM_ATTEMPTS)
                    .<Callable<RuntimeException>>mapToObj(n -> () -> {
                        startLine.await();
                        try {
                            engine.claim(taskId, "clerk-" + n);
                            won.incrementAndGet();
                            return null;
                        } catch (TaskAlreadyClaimedException expected) {
                            lostTyped.incrementAndGet();
                            return null;
                        } catch (RuntimeException unexpected) {
                            return unexpected;
                        }
                    })
                    .toList();
            List<Future<RuntimeException>> running = attempts.stream().map(clerks::submit).toList();
            startLine.countDown();

            List<RuntimeException> unexpected = running.stream()
                    .map(ConcurrentClaimIntegrationTest::join)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(unexpected)
                    .as("a losing claim must be the typed conflict, never a leaked engine failure")
                    .isEmpty();
        } finally {
            clerks.shutdownNow();
            clerks.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(won).as("exactly one clerk holds the item").hasValue(1);
        assertThat(lostTyped).hasValue(CLAIM_ATTEMPTS - 1);

        ClerkTask held = engine.findTask(taskId).orElseThrow();
        assertThat(held.assignedTo()).as("the winner's name is on the item").isPresent();
        assertThat(engine.openTasks(visit))
                .as("the race changed who holds the item, never how many there are")
                .hasSize(1);
    }

    private static final TenantRef SITE_A = new TenantRef("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static RuntimeException join(Future<RuntimeException> future) {
        try {
            return future.get(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
