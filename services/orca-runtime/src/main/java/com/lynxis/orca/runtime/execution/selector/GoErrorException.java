package com.lynxis.orca.runtime.execution.selector;

/**
 * Go's plain {@code error} return position. {@code notFound} carries the
 * {@code gorm.ErrRecordNotFound} identity the Go code branches on for error codes.
 */
public class GoErrorException extends Exception {

    private final boolean notFound;

    public GoErrorException(String message) {
        this(message, false);
    }

    public GoErrorException(String message, boolean notFound) {
        super(message, null, false, false);
        this.notFound = notFound;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
