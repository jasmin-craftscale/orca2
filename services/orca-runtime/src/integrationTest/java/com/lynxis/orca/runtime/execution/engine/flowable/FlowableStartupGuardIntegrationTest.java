package com.lynxis.orca.runtime.execution.engine.flowable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The guard must catch both halves of the shadow's omission class: an engine configured
 * WITHOUT the {@code orca:} functions boots happily and dies at first evaluation — the
 * probe turns that into a startup refusal; a properly configured engine passes.
 */
class FlowableStartupGuardIntegrationTest {

    private static FreshMssql.ProvisionedDatabase db;
    private static ProcessEngine withFunctions;
    private static ProcessEngine withoutFunctions;

    @BeforeAll
    static void bootTwoEnginesOnOneFlywaySchema() {
        db = FreshMssql.freshRuntimeDatabase("flowable_guard", true);
        FreshMssql.migrateRuntime(db);
        db = FreshMssql.serviceLogin(db, "orca_runtime_guard", "Gu4rd!Runtime2026", "runtime");

        withFunctions = configured(true).buildProcessEngine();
        withoutFunctions = configured(false).buildProcessEngine();
    }

    private static ProcessEngineConfiguration configured(boolean registerOrcaFunctions) {
        ProcessEngineConfiguration configuration = ProcessEngineConfiguration
                .createStandaloneProcessEngineConfiguration()
                .setJdbcUrl(db.jdbcUrl())
                .setJdbcUsername(db.username())
                .setJdbcPassword(db.password())
                .setJdbcDriver("com.microsoft.sqlserver.jdbc.SQLServerDriver")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE)
                .setAsyncExecutorActivate(false)
                .setHistory("activity");
        ProcessEngineConfigurationImpl impl = (ProcessEngineConfigurationImpl) configuration;
        impl.setDatabaseSchema("runtime");
        impl.setDisableIdmEngine(true);
        impl.setDisableEventRegistry(true);
        impl.setEngineName(registerOrcaFunctions ? "guard-with-functions" : "guard-without-functions");
        if (registerOrcaFunctions) {
            impl.setCustomFlowableFunctionDelegates(OrcaElFunctions.all());
        }
        return configuration;
    }

    @AfterAll
    static void shutDown() {
        if (withFunctions != null) {
            withFunctions.close();
        }
        if (withoutFunctions != null) {
            withoutFunctions.close();
        }
    }

    @Test
    void aProperlyConfiguredEnginePassesTheGuard() {
        assertThatCode(() -> new FlowableStartupGuard(withFunctions).assertReady())
                .doesNotThrowAnyException();
    }

    @Test
    void anEngineWithoutOrcaFunctionsIsRefusedAtStartupNotAtFirstDecision() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new FlowableStartupGuard(withoutFunctions).assertReady())
                .withMessageContaining("orca:");
    }
}
