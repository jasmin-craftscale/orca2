package com.lynxis.orca.runtime.integration.connector;

/**
 * The connector could not be reached, or the call never completed.
 *
 * <p>Distinct from any HTTP status on purpose. A refused connection, a DNS failure or a
 * timeout is the absence of an answer, and a workflow's response branches were authored for
 * answers. Mapping this to a status would send the visit down a path its author believes
 * means something — the failure has to stay a failure so the engine can retry it and an
 * operator can see it.
 */
public class ConnectorCallFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConnectorCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
