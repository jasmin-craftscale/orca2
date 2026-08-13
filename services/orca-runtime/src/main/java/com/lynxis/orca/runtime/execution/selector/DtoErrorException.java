package com.lynxis.orca.runtime.execution.selector;

/** Go's {@code *dto.Error} return position: an ORCA-authored error with a stable message. */
public class DtoErrorException extends Exception {

    public DtoErrorException(String message) {
        super(message, null, false, false);
    }
}
