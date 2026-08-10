package com.lynxis.orca.runtime.execution.engine.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.job.service.impl.asyncexecutor.DefaultAsyncJobExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Crash-resume. An engine instance dies mid-visit with an async job
 * locked; a second engine instance on the same database picks the expired lock up and
 * finishes the visit. The invariant under test is the NEGATIVE that fails against Go
 * today: <b>no execution is left RUNNING with no pending job</b> — a crashed step either
 * completes on another node or stays claimable, never a zombie.
 */
class CrashResumeIntegrationTest {

    private static FreshMssql.ProvisionedDatabase admin;
    private static FreshMssql.ProvisionedDatabase victimDb;
    private static FreshMssql.ProvisionedDatabase resumerDb;
    private static ProcessEngine crashingEngine;
    private static ProcessEngine resumingEngine;

    /** First execution attempt parks forever (the "crash"); later attempts sail through. */
    static final CountDownLatch FIRST_ATTEMPT_PARKED = new CountDownLatch(1);
    static final CountDownLatch RELEASE_PARKED_ATTEMPT = new CountDownLatch(1);
    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    public static class CrashOnceDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            if (ATTEMPTS.incrementAndGet() == 1) {
                FIRST_ATTEMPT_PARKED.countDown();
                try {
                    // Holds the job lock well past its expiry — the executor thread is
                    // "dead" as far as the rest of the system can tell.
                    RELEASE_PARKED_ATTEMPT.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("first attempt crashed (simulated)");
            }
        }
    }

    @BeforeAll
    static void bootTwoEnginesOnOneDatabase() {
        admin = FreshMssql.freshRuntimeDatabase("flowable_crashresume", true);
        FreshMssql.migrateRuntime(admin);
        // The victim gets its OWN login so its server-side sessions can be killed by name
        // — that is what a crash IS: the connection dies and SQL Server rolls its open
        // transaction back, releasing every lock. (A merely-parked thread keeps its
        // locks and deadlocks the resumer — found out by writing exactly that bug.)
        victimDb = FreshMssql.serviceLogin(admin, "orca_runtime_crash_victim", "Cr4sh!Runtime2026", "runtime");
        resumerDb = FreshMssql.serviceLogin(admin, "orca_runtime_crash_resumer", "Cr4sh!Runtime2026", "runtime");

        crashingEngine = engine("crash-victim", victimDb);
        resumingEngine = engine("crash-resumer", resumerDb);
    }

    private static ProcessEngine engine(String name, FreshMssql.ProvisionedDatabase db) {
        ProcessEngineConfiguration configuration = ProcessEngineConfiguration
                .createStandaloneProcessEngineConfiguration()
                .setJdbcUrl(db.jdbcUrl())
                .setJdbcUsername(db.username())
                .setJdbcPassword(db.password())
                .setJdbcDriver("com.microsoft.sqlserver.jdbc.SQLServerDriver")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE)
                .setAsyncExecutorActivate(true)
                .setHistory("activity");
        ProcessEngineConfigurationImpl impl = (ProcessEngineConfigurationImpl) configuration;
        impl.setDatabaseSchema("runtime");
        impl.setDisableIdmEngine(true);
        impl.setDisableEventRegistry(true);
        impl.setEngineName(name);
        DefaultAsyncJobExecutor asyncExecutor = new DefaultAsyncJobExecutor();
        asyncExecutor.setExecuteAsyncRunnableFactory(new TenantBindingJobRunnableFactory());
        // Tight recovery timings so the test observes the takeover in seconds: a lock
        // expires after 2s and both engines sweep for expired locks every second.
        asyncExecutor.setAsyncJobLockTimeInMillis(2_000);
        asyncExecutor.setResetExpiredJobsInterval(1_000);
        asyncExecutor.setDefaultAsyncJobAcquireWaitTimeInMillis(500);
        impl.setAsyncExecutor(asyncExecutor);
        return configuration.buildProcessEngine();
    }

    @AfterAll
    static void shutDown() {
        RELEASE_PARKED_ATTEMPT.countDown(); // let the parked thread die so the JVM can exit
        if (crashingEngine != null) {
            crashingEngine.close();
        }
        if (resumingEngine != null) {
            resumingEngine.close();
        }
    }

    @Test
    void aCrashedStepResumesOnAnotherEngineAndLeavesNoZombie() throws Exception {
        byte[] bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://lynxis.com/orca">
                  <process id="proc_crash_resume" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="step"/>
                    <serviceTask id="step" flowable:async="true"
                                 flowable:class="%s"/>
                    <sequenceFlow id="f2" sourceRef="step" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(CrashOnceDelegate.class.getName()).getBytes(StandardCharsets.UTF_8);
        // Deploy through the victim; definitions are shared state in the one database.
        crashingEngine.getRepositoryService().createDeployment()
                .addBytes("proc_crash_resume.bpmn20.xml", bpmn).deploy();

        String instanceId = crashingEngine.getRuntimeService().createProcessInstanceBuilder()
                .processDefinitionKey("proc_crash_resume")
                .overrideProcessDefinitionTenantId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                .start()
                .getId();

        // The victim's executor thread is now parked inside the delegate, lock held.
        FIRST_ATTEMPT_PARKED.await();

        // Crash the victim: kill its database sessions. Open transactions roll back and
        // their locks release; the job row keeps the victim's lock owner until it expires.
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                        admin.jdbcUrl(), admin.username(), admin.password());
                java.sql.Statement kill = connection.createStatement()) {
            var sessions = kill.executeQuery(
                    "SELECT session_id FROM sys.dm_exec_sessions WHERE login_name = 'orca_runtime_crash_victim'");
            var ids = new java.util.ArrayList<Integer>();
            while (sessions.next()) {
                ids.add(sessions.getInt(1));
            }
            for (int id : ids) {
                try (java.sql.Statement s2 = connection.createStatement()) {
                    s2.executeUpdate("KILL " + id);
                } catch (java.sql.SQLException ignored) {
                    // a session may have vanished between listing and killing
                }
            }
        }

        // The resumer must take the job over once the lock expires and finish the visit.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                resumingEngine.getRuntimeService().createProcessInstanceQuery()
                        .processInstanceId(instanceId).count())
                .as("the crashed visit must complete on the second engine")
                .isZero());

        // The negative that fails against Go today: nothing RUNNING with no pending job.
        long liveExecutions = resumingEngine.getRuntimeService().createProcessInstanceQuery().count();
        long pendingJobs = resumingEngine.getManagementService().createJobQuery().count()
                + resumingEngine.getManagementService().createTimerJobQuery().count()
                + resumingEngine.getManagementService().createDeadLetterJobQuery().count();
        assertThat(liveExecutions)
                .as("every live execution must hold a pending job; RUNNING with none is a zombie")
                .isLessThanOrEqualTo(pendingJobs);
    }
}
