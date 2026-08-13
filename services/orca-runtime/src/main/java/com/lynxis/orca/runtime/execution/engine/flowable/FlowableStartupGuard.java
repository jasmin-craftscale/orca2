package com.lynxis.orca.runtime.execution.engine.flowable;

import java.util.Map;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.ProcessEngineImpl;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.variable.service.impl.el.NoExecutionVariableScope;

/**
 * Refuses to serve on a wrong or half-configured engine (nothing fails
 * silently). Two assertions, each the loud version of a defect that was once quiet:
 *
 * <ul>
 *   <li><b>Vendored schema version.</b> The engine runs with
 *       {@code database-schema-update=false} on a Flyway-built schema; this pins the DDL
 *       actually applied to the exact version this binary embeds, with an ORCA-worded
 *       error naming the re-vendor script instead of Flowable's "set schema update to
 *       true" hint (which is precisely what must never be done here).</li>
 *   <li><b>{@code orca:} functions resolve.</b> The shadow's omission class: an engine
 *       that boots happily without {@code orca:cmp}/{@code orca:grp} registered and then
 *       fails every compiled decision at first evaluation. Each registered function is
 *       resolved and evaluated once against a probe expression before the service takes
 *       traffic.</li>
 * </ul>
 */
public final class FlowableStartupGuard {

    /** The version V003 vendors — bump together with scripts/vendor-flowable-ddl.sh. */
    static final String VENDORED_SCHEMA_VERSION = "8.0.0.0";

    /** Probe → expected value; every registered orca: function appears here. */
    private static final Map<String, Boolean> FUNCTION_PROBES = Map.of(
            "${orca:cmp('is equal to', '1', '1.0')}", true,
            "${orca:grp(true)}", true);

    private final ProcessEngine engine;

    public FlowableStartupGuard(ProcessEngine engine) {
        this.engine = engine;
    }

    public void assertReady() {
        assertVendoredSchemaVersion();
        assertOrcaFunctionsResolve();
    }

    private void assertVendoredSchemaVersion() {
        String dbVersion = engine.getManagementService().getProperties().get("schema.version");
        if (!VENDORED_SCHEMA_VERSION.equals(dbVersion)) {
            throw new IllegalStateException("Engine schema version is '" + dbVersion
                    + "' but this binary vendors '" + VENDORED_SCHEMA_VERSION
                    + "' (V003). Never enable database-schema-update — re-vendor the DDL with"
                    + " scripts/vendor-flowable-ddl.sh and ship the diff as the next migration.");
        }
    }

    private void assertOrcaFunctionsResolve() {
        ProcessEngineConfigurationImpl configuration =
                ((ProcessEngineImpl) engine).getProcessEngineConfiguration();
        for (Map.Entry<String, Boolean> probe : FUNCTION_PROBES.entrySet()) {
            Object value;
            try {
                value = engine.getManagementService().executeCommand(context -> {
                    Expression expression = configuration.getExpressionManager()
                            .createExpression(probe.getKey());
                    return expression.getValue(NoExecutionVariableScope.getSharedInstance());
                });
            } catch (RuntimeException e) {
                throw new IllegalStateException("orca: EL function probe " + probe.getKey()
                        + " failed to resolve — the engine was configured without OrcaElFunctions,"
                        + " and every compiled decision would die at first evaluation"
                        + " (the shadow's omission class)", e);
            }
            if (!probe.getValue().equals(value)) {
                throw new IllegalStateException("orca: EL function probe " + probe.getKey()
                        + " evaluated to " + value + " instead of " + probe.getValue()
                        + " — the registered implementation does not carry ORCA semantics");
            }
        }
    }
}
