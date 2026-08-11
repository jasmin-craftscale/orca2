package com.lynxis.orca.runtime.integration.connector;

/**
 * An authored connector shape this runtime does not build requests for.
 *
 * <p>Thrown rather than skipped. A shape that no connector in the estate uses is exactly the
 * shape whose silent omission nobody would notice: the call still goes out, the customer
 * system still answers, and the missing field surfaces days later as a business problem.
 * Failing the step names the field and the reason at the moment it happens.
 */
public class UnsupportedConnectorShapeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedConnectorShapeException(String message) {
        super(message);
    }
}
