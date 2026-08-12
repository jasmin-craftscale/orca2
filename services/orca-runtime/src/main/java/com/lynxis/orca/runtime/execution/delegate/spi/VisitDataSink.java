package com.lynxis.orca.runtime.execution.delegate.spi;

import java.util.Map;

/**
 * Where a visit's dataset goes to become durable. Everything that produces data during a
 * visit — the start payload, a device's reading, a clerk's answer, a connector's extracted
 * fields — writes through here, in the transaction that produced it.
 *
 * <p>It exists as a port so a delegate can record without reaching into the module's
 * persistence internals, and so the compiler and delegate tests keep running against a
 * no-op with no database.
 *
 * <p><b>Visit-scoped, not instance-scoped.</b> A callActivity child is its own engine
 * instance but not its own visit — the platform's subflow results land on the calling
 * execution — so callers pass the ROOT instance id, the same rule the variable mirror
 * follows.
 */
public interface VisitDataSink {

    /**
     * Upserts each entry into the visit's dataset. A key already present is replaced: a key
     * has one current value, and the read path never has to sort history to find it.
     */
    void record(String rootEngineInstanceId, Map<String, Object> dataset);

    /**
     * The same upsert, for callers who hold the visit's ORCA identity rather than an engine
     * instance — the queue finishing a work item, an API completing a visit. Two keys because
     * the two sides of the runtime genuinely hold different handles; one writer either way.
     */
    void recordByExecutionUuid(String executionUuid, Map<String, Object> dataset);

    /** For tests and for the paths that legitimately have nowhere to write yet. */
    VisitDataSink NO_OP = new VisitDataSink() {
        @Override
        public void record(String rootEngineInstanceId, Map<String, Object> dataset) {
        }

        @Override
        public void recordByExecutionUuid(String executionUuid, Map<String, Object> dataset) {
        }
    };
}
