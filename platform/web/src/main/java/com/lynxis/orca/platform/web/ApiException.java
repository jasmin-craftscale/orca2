package com.lynxis.orca.platform.web;

import java.util.List;

import lombok.Getter;

/**
 * The exception a service throws when it wants a specific code on the wire.
 *
 * <p>Anything else that escapes a controller becomes {@code INTERNAL_ERROR} with a
 * fixed message. That asymmetry is deliberate: a failure only reaches a caller
 * with a meaningful code if someone decided what that code should be.
 *
 * <p>{@link #getMessage()} is <strong>not</strong> what the caller sees.
 * {@link #clientMessage} is. Keeping them separate is what lets the log say
 * "constraint pk_outbox violated on core.outbox" while the caller is told
 * "the request conflicts with the current state".
 */
@Getter
public class ApiException extends RuntimeException {

	private final transient ErrorCode errorCode;
	private final transient String clientMessage;
	private final transient List<ApiError> errors;

	public ApiException(ErrorCode errorCode, String clientMessage) {
		this(errorCode, clientMessage, List.of(), null);
	}

	public ApiException(ErrorCode errorCode, String clientMessage, List<ApiError> errors) {
		this(errorCode, clientMessage, errors, null);
	}

	public ApiException(ErrorCode errorCode, String clientMessage, List<ApiError> errors, Throwable cause) {
		// The exception message is for the log. It is built from the CODE, never
		// from the cause, so that a careless handler cannot promote internal text
		// into something client-facing.
		super(errorCode.code() + ": " + clientMessage, cause);
		this.errorCode = errorCode;
		this.clientMessage = clientMessage;
		this.errors = List.copyOf(errors);
	}
}
