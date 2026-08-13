package com.lynxis.orca.runtime.execution.delegate.spi;

import java.time.Duration;
import java.util.Objects;

/**
 * One effect crossing the edge.
 *
 * @param idempotencyKey deterministic per step attempt-set: the engine instance id plus
 *     the element id — a retry of the same step carries the same key
 * @param siteExternalId the site the effect belongs to
 * @param kind what class of effect this is
 * @param nodeUuid the ORCA node the effect implements
 * @param name the authored node name (operator-facing wording travels as data)
 * @param topic the compiled routing topic ({@code orca:topic}), null for display effects
 * @param deadline how long the edge may take before answering {@code UNKNOWN}
 */
public record EdgeCommand(String idempotencyKey, String siteExternalId, EdgeKind kind,
        String nodeUuid, String name, String topic, Duration deadline) {

    public EdgeCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nodeUuid, "nodeUuid");
        Objects.requireNonNull(deadline, "deadline");
    }

    public enum EdgeKind {
        DEVICE_EFFECT,
        DISPLAY
    }
}
