package com.lynxis.orca.runtime.execution.engine.flowable;

import java.nio.charset.StandardCharsets;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;

/**
 * Boots an embedded engine the way production does — on a Flyway-built schema, as a login
 * whose default schema IS {@code runtime}. Shared by every Flowable-side test so the
 * boot decisions live in one place; each of them carries a scar, and three copies would
 * drift on the first one that gets fixed.
 */
final class FlowableTestEngines {

    private FlowableTestEngines() {
    }

    /**
     * @param maxConnections the engine's JDBC pool ceiling — the concurrency tests need more
     *     than the default, and a pool smaller than the racing threads turns a contention
     *     test into a pool-wait test that proves nothing
     */
    static ProcessEngine boot(String databasePrefix, String login, String password, int maxConnections) {
        return bootWithDatabase(databasePrefix, login, password, maxConnections).engine();
    }

    /**
     * Same boot, but the caller also gets the database — as the service login,
     * default schema {@code runtime} — for the suites that put seam repositories
     * beside the engine on the very tables the engine's listeners write.
     */
    static BootedEngine bootWithDatabase(String databasePrefix, String login, String password,
            int maxConnections) {
        return bootWithDatabase(databasePrefix, login, password, maxConnections,
                new java.util.HashMap<>());
    }

    /**
     * @param beans the expression-resolution beans — what production wiring exposes as
     *     Spring beans (the {@code ${orcaConnectorDelegate}}-style delegate references a
     *     compiled definition carries). The SAME map instance is handed to the engine, so
     *     a suite may boot first and put delegates in afterwards — several of them need
     *     the engine's own services to construct.
     */
    static BootedEngine bootWithDatabase(String databasePrefix, String login, String password,
            int maxConnections, java.util.Map<Object, Object> beans) {
        FreshMssql.ProvisionedDatabase db = FreshMssql.freshRuntimeDatabase(databasePrefix, true);
        FreshMssql.migrateRuntime(db);

        // The engine connects the way the service does in production: a dedicated login
        // whose DEFAULT_SCHEMA is `runtime` (schema-per-service enforced by
        // credentials). The vendored DDL and the engine's own SQL are unqualified by
        // design; the login's default schema is what scopes them. (Flowable's
        // databaseTablePrefix path does NOT cover its schema-version probe — tried and
        // rejected: the probe fell back to a 5.x heuristic and refused to boot.)
        db = FreshMssql.serviceLogin(db, login, password, "runtime");

        ProcessEngineConfiguration configuration = ProcessEngineConfiguration
                .createStandaloneProcessEngineConfiguration()
                .setJdbcUrl(db.jdbcUrl())
                .setJdbcUsername(login)
                .setJdbcPassword(password)
                .setJdbcDriver("com.microsoft.sqlserver.jdbc.SQLServerDriver")
                .setJdbcMaxActiveConnections(maxConnections)
                // Flyway owns the schema; the engine only validates it.
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE)
                .setAsyncExecutorActivate(false)
                .setHistory("activity");
        ProcessEngineConfigurationImpl impl = (ProcessEngineConfigurationImpl) configuration;
        impl.setDatabaseSchema("runtime");
        // Process engine ONLY: identity lives in Keycloak and events in Kafka —
        // the IDM and event-registry engines would otherwise auto-boot off the classpath
        // and demand their own tables (the IDM one, finding no ACT_ID_*, reports the
        // schema as a 5.x relic and refuses to start — exactly how this line got here).
        impl.setDisableIdmEngine(true);
        impl.setDisableEventRegistry(true);
        impl.setBeans(beans);

        return new BootedEngine(configuration.buildProcessEngine(), db);
    }

    /** The engine and the database it runs on, credentialed as the service login. */
    record BootedEngine(ProcessEngine engine, FreshMssql.ProvisionedDatabase db) {
    }

    /** A one-process BPMN document around {@code body}. */
    static byte[] bpmn(String processId, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="http://lynxis.com/orca">
                  <process id="%s" isExecutable="true">
                %s  </process>
                </definitions>
                """.formatted(processId, body).getBytes(StandardCharsets.UTF_8);
    }
}
