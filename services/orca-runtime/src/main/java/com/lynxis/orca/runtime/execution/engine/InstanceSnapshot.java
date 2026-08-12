package com.lynxis.orca.runtime.execution.engine;

import java.util.Objects;
import java.util.Optional;

/**
 * Point-in-time view of an instance.
 *
 * @param state     where the instance is in its lifecycle
 * @param waitPoint the wait point the instance is parked at — present exactly when
 *                  {@code state == WAITING}
 * @param tenant    the tenant the instance runs for
 */
public record InstanceSnapshot(InstanceState state, Optional<String> waitPoint, TenantRef tenant) {

    public InstanceSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(waitPoint, "waitPoint");
        Objects.requireNonNull(tenant, "tenant");
        if ((state == InstanceState.WAITING) != waitPoint.isPresent()) {
            throw new IllegalArgumentException("waitPoint must be present exactly when state is WAITING");
        }
    }

    public static InstanceSnapshot running(TenantRef tenant) {
        return new InstanceSnapshot(InstanceState.RUNNING, Optional.empty(), tenant);
    }

    public static InstanceSnapshot waitingAt(String waitPoint, TenantRef tenant) {
        return new InstanceSnapshot(InstanceState.WAITING, Optional.of(waitPoint), tenant);
    }

    public static InstanceSnapshot completed(TenantRef tenant) {
        return new InstanceSnapshot(InstanceState.COMPLETED, Optional.empty(), tenant);
    }

    public static InstanceSnapshot cancelled(TenantRef tenant) {
        return new InstanceSnapshot(InstanceState.CANCELLED, Optional.empty(), tenant);
    }
}
