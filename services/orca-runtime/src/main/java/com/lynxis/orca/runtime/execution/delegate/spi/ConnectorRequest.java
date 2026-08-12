package com.lynxis.orca.runtime.execution.delegate.spi;

import java.util.Objects;

/**
 * One outbound connector call.
 *
 * @param idempotencyKey deterministic per step attempt-set, same shape as edge commands
 * @param nodeUuid the CONNECTOR node making the call
 * @param name the authored connector name — the adapter resolves it to configuration
 * @param siteExternalId the installation's site identifier
 * @param laneId the visit's lane, when known ({@code orcaLaneId} engine variable; null inside
 *     callActivity children, which don't inherit root variables)
 * @param visit the ORCA identity of the running visit, or null when this engine instance has
 *     no ORCA row. The gateway needs it because a connector's field mappings resolve
 *     selectors against it — and it refuses to call without one, since a request built from
 *     unresolved selectors is a request the connector's author never wrote.
 */
public record ConnectorRequest(String idempotencyKey, String nodeUuid, String name,
        String siteExternalId, Long laneId, VisitIdentity.Visit visit) {

    public ConnectorRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(nodeUuid, "nodeUuid");
    }
}
