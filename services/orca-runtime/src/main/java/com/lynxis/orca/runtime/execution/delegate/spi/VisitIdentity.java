package com.lynxis.orca.runtime.execution.delegate.spi;

import java.util.Optional;

/**
 * The ORCA identity of a running visit, given the engine's own instance id.
 *
 * <p>Needed because the two halves of the system name a visit differently: the engine knows
 * a process instance id, while every selector an author writes resolves against the
 * {@code execution} row's key. A connector's field mappings are full of selectors, so the
 * gateway cannot build a request body without crossing that gap.
 *
 * <p>It is a port rather than a repository call in the delegate so that the execution module
 * keeps its persistence to itself — the integration side asks a question and gets an answer,
 * without a second module reaching into the visit tables.
 */
public interface VisitIdentity {

    /** @return empty when no ORCA row claims this instance — an unadmitted or dead visit */
    Optional<Visit> forInstance(String processInstanceId);

    /**
     * @param executionId the internal {@code execution_id} selectors resolve against
     * @param externalId the visit's stable external identifier, which is what a selector's
     *     own text carries
     */
    record Visit(long executionId, String externalId) {
    }
}
